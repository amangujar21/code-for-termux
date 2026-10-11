import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const MAX_BUFFERED_EVENTS = 4000;
const PERMISSION_MODES = new Set(['default', 'acceptEdits', 'plan', 'bypassPermissions']);
const EFFORT_LEVELS = new Set(['low', 'medium', 'high', 'xhigh', 'max']);

/** Push-based async iterable that feeds user messages into a streaming query. */
class InputQueue {
  #items = [];
  #waiters = [];
  #closed = false;

  push(item) {
    if (this.#closed) return;
    const waiter = this.#waiters.shift();
    if (waiter) waiter({ value: item, done: false });
    else this.#items.push(item);
  }

  close() {
    this.#closed = true;
    for (const waiter of this.#waiters.splice(0)) waiter({ value: undefined, done: true });
  }

  [Symbol.asyncIterator]() {
    return {
      next: () => {
        if (this.#items.length) return Promise.resolve({ value: this.#items.shift(), done: false });
        if (this.#closed) return Promise.resolve({ value: undefined, done: true });
        return new Promise((resolve) => this.#waiters.push(resolve));
      },
    };
  }
}

/**
 * One Claude Code conversation kept alive independently of any app connection.
 * Everything the app needs to render is appended to a sequenced event buffer so
 * a reconnecting client can replay what it missed.
 */
export class LiveSession {
  constructor({ id, cwd, model, mode, effort, resume, queryFn, queryOptions, uploadsDir, onClosed }) {
    this.id = id;
    this.cwd = cwd;
    this.model = model ?? null;
    this.mode = PERMISSION_MODES.has(mode) ? mode : 'default';
    this.effort = EFFORT_LEVELS.has(effort) ? effort : null;
    this.state = 'idle';
    this.lastActivity = Date.now();
    this.subscribers = new Set();
    this.pendingPermissions = new Map();
    this.events = [];
    this.seq = 0;
    this.evictedThrough = 0;
    // Transcript that existed on disk before this live session started.
    this.history = [];
    this.closed = false;
    this.uploadsDir = uploadsDir;
    this.onClosed = onClosed;
    this.input = new InputQueue();

    this.query = queryFn({
      prompt: this.input,
      options: {
        ...queryOptions,
        cwd,
        ...(resume ? { resume: id } : { sessionId: id }),
        ...(this.model ? { model: this.model } : {}),
        ...(this.effort ? { effort: this.effort } : {}),
        permissionMode: this.mode,
        allowDangerouslySkipPermissions: true,
        includePartialMessages: true,
        canUseTool: (toolName, input, opts) => this.#askPermission(toolName, input, opts),
      },
    });
    this.init = this.query.initializationResult();
    // Surfaced through info(); avoid an unhandled rejection if nobody awaits it.
    this.init.catch(() => {});
    this.#pump();
  }

  async #pump() {
    try {
      for await (const message of this.query) this.#onSdkMessage(message);
      this.#emit({ type: 'closed' });
    } catch (err) {
      this.#emit({ type: 'error', message: String(err?.message ?? err) });
    } finally {
      this.#setState('idle');
      this.#shutdown();
    }
  }

  #onSdkMessage(message) {
    this.lastActivity = Date.now();
    if (message.type === 'assistant' || message.type === 'result') {
      // The complete message supersedes its streamed deltas; drop them from replay.
      const parent = message.parent_tool_use_id ?? null;
      this.events = this.events.filter(
        (e) => !(e.event.type === 'sdk' && e.event.message.type === 'stream_event' &&
          (message.type === 'result' || (e.event.message.parent_tool_use_id ?? null) === parent)),
      );
    }
    if (message.type === 'system' && message.subtype === 'init') {
      this.model = message.model ?? this.model;
      this.mode = message.permissionMode ?? this.mode;
    }
    if (message.type === 'system' && message.subtype === 'status' && message.permissionMode) {
      this.mode = message.permissionMode;
    }
    this.#emit({ type: 'sdk', message });
    if (message.type === 'result') this.#setState('idle');
  }

  #emit(event) {
    const entry = { seq: ++this.seq, event };
    this.events.push(entry);
    if (this.events.length > MAX_BUFFERED_EVENTS) {
      const evicted = this.events.splice(0, this.events.length - MAX_BUFFERED_EVENTS);
      this.evictedThrough = evicted[evicted.length - 1].seq;
    }
    for (const send of this.subscribers) send(this.id, entry);
  }

  #setState(state) {
    if (this.state === state) return;
    this.state = state;
    this.#emit({ type: 'state', state, model: this.model, mode: this.mode, effort: this.effort });
  }

  #askPermission(toolName, input, opts) {
    return new Promise((resolve) => {
      const requestId = opts.toolUseID ?? randomUUID();
      const settle = (result) => {
        if (!this.pendingPermissions.delete(requestId)) return;
        this.#emit({ type: 'permission.resolved', requestId, behavior: result.behavior });
        this.#setState('running');
        resolve(result);
      };
      this.pendingPermissions.set(requestId, { input, suggestions: opts.suggestions ?? [], settle });
      opts.signal?.addEventListener('abort', () => settle({ behavior: 'deny', message: 'Cancelled' }), { once: true });
      this.#emit({
        type: 'permission.request',
        requestId,
        toolName,
        input,
        suggestions: opts.suggestions ?? [],
        blockedPath: opts.blockedPath ?? null,
        decisionReason: opts.decisionReason ?? null,
        agentId: opts.agentID ?? null,
      });
      this.#setState('waiting_permission');
    });
  }

  /** decision: 'allow' | 'allow_always' | 'deny' */
  resolvePermission(requestId, decision, { message, updatedInput } = {}) {
    const pending = this.pendingPermissions.get(requestId);
    if (!pending) return false;
    if (decision === 'deny') {
      pending.settle({ behavior: 'deny', message: message || 'The user denied this action.' });
    } else {
      pending.settle({
        behavior: 'allow',
        updatedInput: updatedInput ?? pending.input,
        ...(decision === 'allow_always' && pending.suggestions.length
          ? { updatedPermissions: pending.suggestions }
          : {}),
      });
    }
    return true;
  }

  async sendUserMessage({ text = '', attachments = [] }) {
    if (this.closed) throw new Error('Session is closed');
    const content = [];
    const fileNotes = [];
    for (const attachment of attachments) {
      if (attachment.kind === 'image') {
        content.push({
          type: 'image',
          source: { type: 'base64', media_type: attachment.mediaType, data: attachment.data },
        });
      } else {
        fileNotes.push(await this.#saveUpload(attachment));
      }
    }
    const fullText = [text, ...fileNotes.map((p) => `[Attached file: ${p}]`)].filter(Boolean).join('\n');
    if (fullText) content.push({ type: 'text', text: fullText });
    if (!content.length) throw new Error('Empty message');

    this.lastActivity = Date.now();
    // Echo so every connected client (and any later replay) shows the prompt.
    this.#emit({
      type: 'user.echo',
      text,
      attachments: attachments.map((a) => ({ kind: a.kind, name: a.name ?? null, mediaType: a.mediaType ?? null })),
    });
    this.#setState('running');
    this.input.push({
      type: 'user',
      message: { role: 'user', content },
      parent_tool_use_id: null,
      session_id: this.id,
    });
  }

  async #saveUpload(attachment) {
    const dir = path.join(this.uploadsDir, this.id);
    await mkdir(dir, { recursive: true });
    const safeName = path.basename(String(attachment.name || 'file')).replace(/[^\w.\-]+/g, '_') || 'file';
    const target = path.join(dir, `${Date.now()}-${safeName}`);
    await writeFile(target, Buffer.from(attachment.data, 'base64'));
    return target;
  }

  async interrupt() {
    for (const pending of [...this.pendingPermissions.values()]) {
      pending.settle({ behavior: 'deny', message: 'Interrupted by user', interrupt: true });
    }
    await this.query.interrupt();
  }

  async setModel(model) {
    await this.query.setModel(model || undefined);
    this.model = model || null;
    this.#emitSettings();
  }

  async setMode(mode) {
    if (!PERMISSION_MODES.has(mode)) throw new Error(`Unsupported permission mode: ${mode}`);
    await this.query.setPermissionMode(mode);
    this.mode = mode;
    this.#emitSettings();
  }

  async setEffort(effort) {
    if (effort !== null && !EFFORT_LEVELS.has(effort)) throw new Error(`Unsupported effort: ${effort}`);
    await this.query.applyFlagSettings({ effortLevel: effort });
    this.effort = effort;
    this.#emitSettings();
  }

  #emitSettings() {
    this.#emit({ type: 'state', state: this.state, model: this.model, mode: this.mode, effort: this.effort });
  }

  async info() {
    const init = await this.init;
    return {
      sessionId: this.id,
      cwd: this.cwd,
      state: this.state,
      model: this.model,
      mode: this.mode,
      effort: this.effort,
      seq: this.seq,
      commands: init.commands ?? [],
      models: init.models ?? [],
      account: init.account ?? {},
      claudeCodeVersion: init.claude_code_version ?? null,
      pendingPermissions: this.#pendingPermissionEvents(),
    };
  }

  #pendingPermissionEvents() {
    return this.events
      .filter((e) => e.event.type === 'permission.request' && this.pendingPermissions.has(e.event.requestId))
      .map((e) => e.event);
  }

  /** Events after `afterSeq`; `gap` is true when some of them were already evicted. */
  replay(afterSeq = 0) {
    return { gap: afterSeq < this.evictedThrough, events: this.events.filter((e) => e.seq > afterSeq) };
  }

  /** Drop buffered events once the on-disk transcript covers them (only safe while idle). */
  resetBuffer(history) {
    this.history = history;
    this.evictedThrough = this.seq;
    this.events = this.events.filter(
      (e) => e.event.type === 'permission.request' && this.pendingPermissions.has(e.event.requestId),
    );
  }

  close() {
    if (this.closed) return;
    this.input.close();
    try {
      this.query.close();
    } catch {
      // already gone
    }
    this.#shutdown();
  }

  #shutdown() {
    if (this.closed) return;
    this.closed = true;
    for (const pending of [...this.pendingPermissions.values()]) {
      pending.settle({ behavior: 'deny', message: 'Session closed' });
    }
    this.onClosed?.(this);
  }
}
