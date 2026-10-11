#!/usr/bin/env node
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { mkdir, readdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { WebSocketServer } from 'ws';
import { LiveSession } from './session.js';

export const BRIDGE_VERSION = '0.3.0';
export const PROTOCOL_VERSION = 1;

const IDLE_SESSION_TTL_MS = 30 * 60 * 1000;
const MAX_PAYLOAD_BYTES = 48 * 1024 * 1024;
const ANDROID_SHARED_STORAGE = '/storage/emulated/0';
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function tokensMatch(expected, given) {
  const a = Buffer.from(String(expected));
  const b = Buffer.from(String(given ?? ''));
  return a.length === b.length && timingSafeEqual(a, b);
}

/**
 * @param {{ token: string, home?: string, bridgeHome?: string, claudePath?: string, sharedStorage?: string | null, sdk: object }} config
 *   `sdk` is the Agent SDK module (injected so tests can fake it).
 */
export function createBridge(config) {
  const { sdk, token } = config;
  if (!token || token.length < 16) throw new Error('A pairing token of at least 16 characters is required');
  const home = config.home ?? os.homedir();
  const bridgeHome = config.bridgeHome ?? path.join(home, '.claude-bridge');
  const uploadsDir = path.join(bridgeHome, 'uploads');
  // The phone's own storage. Termux can only read it once the user has granted it the storage permission.
  const sharedStorage = config.sharedStorage !== undefined
    ? config.sharedStorage
    : process.platform === 'android' ? ANDROID_SHARED_STORAGE : null;
  const queryOptions = {
    ...(config.claudePath ? { pathToClaudeCodeExecutable: config.claudePath } : {}),
    stderr: (data) => process.stderr.write(`[claude] ${data}`),
  };

  /** @type {Map<string, LiveSession>} */
  const live = new Map();
  let statusCache = null;

  function startSession({ id, cwd, model, mode, effort, resume }) {
    const session = new LiveSession({
      id, cwd, model, mode, effort, resume,
      queryFn: sdk.query,
      queryOptions,
      uploadsDir,
      onClosed: (s) => {
        if (live.get(s.id) === s) live.delete(s.id);
      },
    });
    live.set(id, session);
    return session;
  }

  function requireLive(sessionId) {
    const session = live.get(sessionId);
    if (!session) throw new Error('Session is not open');
    return session;
  }

  async function loadHistory(sessionId) {
    try {
      return await sdk.getSessionMessages(sessionId);
    } catch {
      return [];
    }
  }

  const handlers = {
    async hello() {
      return {
        bridgeVersion: BRIDGE_VERSION,
        protocolVersion: PROTOCOL_VERSION,
        home,
        platform: process.platform,
        node: process.version,
      };
    },

    /** Account, models and commands without opening a conversation (used by onboarding). */
    async 'bridge.status'({ fresh } = {}) {
      if (!fresh && statusCache && Date.now() - statusCache.at < 60_000) return statusCache.value;
      // A running session only knows the account it started with, so a fresh look needs a new process.
      const any = fresh ? null : [...live.values()][0];
      let value;
      if (any) {
        value = await any.info();
      } else {
        const probe = startSession({ id: randomUUID(), cwd: home });
        try {
          value = await probe.info();
        } finally {
          probe.close();
        }
      }
      value = {
        account: value.account,
        models: value.models,
        commands: value.commands,
        claudeCodeVersion: value.claudeCodeVersion,
      };
      statusCache = { at: Date.now(), value };
      return value;
    },

    async 'sessions.list'({ dir, limit = 200, offset = 0 }) {
      const sessions = await sdk.listSessions({ ...(dir ? { dir } : {}), limit, offset });
      const seen = new Set();
      const rows = sessions.map((s) => {
        seen.add(s.sessionId);
        const liveSession = live.get(s.sessionId);
        return { ...s, state: liveSession?.state ?? null };
      });
      // Brand-new sessions have no transcript on disk until their first turn.
      for (const s of live.values()) {
        if (seen.has(s.id) || s.seq === 0) continue;
        if (dir && s.cwd !== dir) continue;
        rows.unshift({ sessionId: s.id, summary: 'New session', lastModified: s.lastActivity, cwd: s.cwd, state: s.state });
      }
      return { sessions: rows };
    },

    async 'session.open'({ sessionId, cwd, model, mode, effort, afterSeq = 0 }, client) {
      let session = sessionId ? live.get(sessionId) : null;
      if (!session) {
        if (sessionId && !UUID_RE.test(sessionId)) throw new Error('Invalid session id');
        let resolvedCwd = cwd;
        if (sessionId && !resolvedCwd) {
          resolvedCwd = (await sdk.getSessionInfo(sessionId))?.cwd;
        }
        resolvedCwd ||= home;
        if (!existsSync(resolvedCwd)) throw new Error(`Folder does not exist: ${resolvedCwd}`);
        const history = sessionId ? await loadHistory(sessionId) : [];
        session = startSession({
          id: sessionId ?? randomUUID(),
          cwd: resolvedCwd,
          model, mode, effort,
          resume: Boolean(sessionId),
        });
        session.history = history;
      }
      let { gap, events } = session.replay(afterSeq);
      let resetHistory = afterSeq === 0;
      if (gap && session.state === 'idle') {
        session.resetBuffer(await loadHistory(session.id));
        ({ events } = session.replay(session.evictedThrough));
        resetHistory = true;
      }
      client.subscribe(session);
      const info = await session.info();
      return { ...info, history: resetHistory || gap ? session.history : null, events, gap };
    },

    async 'session.leave'({ sessionId }, client) {
      const session = live.get(sessionId);
      if (session) client.unsubscribe(session);
      return {};
    },

    async 'session.stop'({ sessionId }) {
      live.get(sessionId)?.close();
      return {};
    },

    async 'user.message'({ sessionId, text, attachments }) {
      await requireLive(sessionId).sendUserMessage({ text, attachments });
      return {};
    },

    async interrupt({ sessionId }) {
      await requireLive(sessionId).interrupt();
      return {};
    },

    async 'permission.response'({ sessionId, requestId, decision, message, updatedInput }) {
      const ok = requireLive(sessionId).resolvePermission(requestId, decision, { message, updatedInput });
      if (!ok) throw new Error('That permission request is no longer pending');
      return {};
    },

    async 'set.model'({ sessionId, model }) {
      await requireLive(sessionId).setModel(model);
      return {};
    },

    async 'set.mode'({ sessionId, mode }) {
      await requireLive(sessionId).setMode(mode);
      return {};
    },

    async 'set.effort'({ sessionId, effort }) {
      await requireLive(sessionId).setEffort(effort ?? null);
      return {};
    },

    async 'context.usage'({ sessionId }) {
      return await requireLive(sessionId).query.getContextUsage();
    },

    async 'mcp.status'({ sessionId }) {
      return { servers: await requireLive(sessionId).query.mcpServerStatus() };
    },

    async 'commands.list'({ sessionId }) {
      return { commands: await requireLive(sessionId).query.supportedCommands() };
    },

    async 'session.rename'({ sessionId, title }) {
      await sdk.renameSession(sessionId, String(title).slice(0, 200));
      return {};
    },

    async 'session.delete'({ sessionId }) {
      live.get(sessionId)?.close();
      await sdk.deleteSession(sessionId);
      return {};
    },

    async 'fs.list'({ path: dir }) {
      const target = path.resolve(dir || home);
      const entries = await readdir(target, { withFileTypes: true });
      const dirs = entries
        .filter((e) => e.isDirectory() || e.isSymbolicLink())
        .map((e) => e.name)
        .sort((a, b) => a.localeCompare(b));
      const parent = path.dirname(target);
      const storage = sharedStorage
        ? { path: sharedStorage, accessible: await readdir(sharedStorage).then(() => true, () => false) }
        : null;
      return { path: target, parent: parent === target ? null : parent, home, storage, dirs };
    },

    async 'fs.mkdir'({ path: dir }) {
      if (!dir || !path.isAbsolute(dir)) throw new Error('An absolute path is required');
      await mkdir(dir, { recursive: true });
      return { path: path.resolve(dir) };
    },
  };

  const httpServer = createServer((req, res) => {
    // Unauthenticated liveness probe; reveals nothing beyond "a bridge is here".
    if (req.url === '/health') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true, bridgeVersion: BRIDGE_VERSION, protocolVersion: PROTOCOL_VERSION }));
      return;
    }
    res.writeHead(404).end();
  });

  const wss = new WebSocketServer({ server: httpServer, maxPayload: MAX_PAYLOAD_BYTES });

  wss.on('connection', (ws, req) => {
    const headerToken = /^Bearer (.+)$/.exec(req.headers.authorization ?? '')?.[1];
    let authed = tokensMatch(token, headerToken);
    const subscriptions = new Set();
    const send = (payload) => {
      if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
    };
    const onEvent = (sessionId, entry) => send({ type: 'event', sessionId, seq: entry.seq, event: entry.event });
    const client = {
      subscribe(session) {
        session.subscribers.add(onEvent);
        subscriptions.add(session);
      },
      unsubscribe(session) {
        session.subscribers.delete(onEvent);
        subscriptions.delete(session);
      },
    };

    ws.on('message', async (raw) => {
      let request;
      try {
        request = JSON.parse(raw.toString());
      } catch {
        return send({ type: 'error', message: 'Malformed JSON' });
      }
      const { id, type, ...params } = request;
      if (!authed) {
        authed = type === 'hello' && tokensMatch(token, params.token);
        if (!authed) {
          send({ replyTo: id, ok: false, error: 'unauthorized' });
          return ws.close(4401, 'unauthorized');
        }
      }
      const handler = Object.hasOwn(handlers, type) ? handlers[type] : null;
      if (!handler) return send({ replyTo: id, ok: false, error: `Unknown request type: ${type}` });
      try {
        send({ replyTo: id, ok: true, data: await handler(params, client) });
      } catch (err) {
        send({ replyTo: id, ok: false, error: String(err?.message ?? err) });
      }
    });

    ws.on('close', () => {
      for (const session of subscriptions) session.subscribers.delete(onEvent);
      subscriptions.clear();
    });
  });

  const reaper = setInterval(() => {
    const cutoff = Date.now() - IDLE_SESSION_TTL_MS;
    for (const session of live.values()) {
      if (session.state === 'idle' && session.subscribers.size === 0 && session.lastActivity < cutoff) {
        session.close();
      }
    }
  }, 60_000);
  reaper.unref();

  return {
    live,
    listen(port = 0, host = '127.0.0.1') {
      return new Promise((resolve, reject) => {
        httpServer.once('error', reject);
        httpServer.listen(port, host, () => resolve(httpServer.address().port));
      });
    },
    async close() {
      clearInterval(reaper);
      for (const session of [...live.values()]) session.close();
      for (const ws of wss.clients) ws.terminate();
      await new Promise((resolve) => httpServer.close(resolve));
    },
  };
}

function loadConfig() {
  const bridgeHome = process.env.CLAUDE_BRIDGE_HOME ?? path.join(os.homedir(), '.claude-bridge');
  const file = path.join(bridgeHome, 'config.json');
  const fromFile = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
  return {
    bridgeHome,
    token: process.env.CLAUDE_BRIDGE_TOKEN ?? fromFile.token,
    port: Number(process.env.CLAUDE_BRIDGE_PORT ?? fromFile.port ?? 8765),
    claudePath: process.env.CLAUDE_BIN ?? fromFile.claudePath,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const config = loadConfig();
  if (!config.token) {
    console.error(`No pairing token. Run the setup from the app, or set CLAUDE_BRIDGE_TOKEN (see ${path.dirname(fileURLToPath(import.meta.url))}).`);
    process.exit(1);
  }
  const sdk = await import('@anthropic-ai/claude-agent-sdk');
  const bridge = createBridge({ ...config, sdk });
  const port = await bridge.listen(config.port);
  console.log(`claude-termux-bridge ${BRIDGE_VERSION} listening on 127.0.0.1:${port}`);
  const shutdown = () => bridge.close().finally(() => process.exit(0));
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
