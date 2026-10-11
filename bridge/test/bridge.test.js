import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import WebSocket from 'ws';
import { createBridge } from '../server.js';

const TOKEN = 'test-token-0123456789abcdef';

/** Minimal stand-in for the Agent SDK: echoes each prompt, asking permission when it contains "write". */
function fakeSdk() {
  const calls = { setModel: [], setMode: [], effort: [], deleted: [] };
  const sdk = {
    calls,
    query({ prompt, options }) {
      let closed = false;
      let wake;
      const closedSignal = new Promise((resolve) => (wake = resolve));
      async function* run() {
        const it = prompt[Symbol.asyncIterator]();
        while (!closed) {
          const next = await Promise.race([it.next(), closedSignal.then(() => ({ done: true }))]);
          if (next.done) return;
          const text = next.value.message.content.find((b) => b.type === 'text')?.text ?? '';
          yield { type: 'system', subtype: 'init', model: 'fake-model', permissionMode: options.permissionMode };
          if (text.includes('write')) {
            const decision = await options.canUseTool('Write', { file_path: 'a.txt' }, {
              toolUseID: 'tool-1',
              suggestions: [{ type: 'addRules', rules: [{ toolName: 'Write' }], behavior: 'allow', destination: 'session' }],
            });
            yield { type: 'assistant', parent_tool_use_id: null, message: { content: [{ type: 'text', text: decision.behavior }] } };
          } else {
            yield { type: 'stream_event', parent_tool_use_id: null, event: { type: 'content_block_delta' } };
            yield { type: 'assistant', parent_tool_use_id: null, message: { content: [{ type: 'text', text: `echo:${text}` }] } };
          }
          yield { type: 'result', subtype: 'success', result: 'done' };
        }
      }
      const gen = run();
      return Object.assign(gen, {
        initializationResult: async () => ({
          commands: [{ name: 'compact', description: 'Compact', argumentHint: '' }],
          models: [{ value: 'default', displayName: 'Default', description: '' }],
          account: { email: 'me@example.com' },
          claude_code_version: '9.9.9',
        }),
        interrupt: async () => {},
        setModel: async (m) => calls.setModel.push(m),
        setPermissionMode: async (m) => calls.setMode.push(m),
        applyFlagSettings: async (s) => calls.effort.push(s.effortLevel),
        getContextUsage: async () => ({ totalTokens: 1, maxTokens: 2 }),
        mcpServerStatus: async () => [],
        supportedCommands: async () => [],
        close: () => {
          closed = true;
          wake();
        },
      });
    },
    listSessions: async () => [{ sessionId: '11111111-1111-4111-8111-111111111111', summary: 'Old', lastModified: 1, cwd: os.tmpdir() }],
    getSessionMessages: async () => [{ type: 'user', uuid: 'u1', message: { role: 'user', content: 'earlier' } }],
    getSessionInfo: async () => ({ cwd: os.tmpdir() }),
    renameSession: async () => {},
    deleteSession: async (id) => calls.deleted.push(id),
  };
  return sdk;
}

function client(port, headers) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}`, { headers });
  const pending = new Map();
  const events = [];
  const waiters = [];
  let nextId = 1;
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.replyTo !== undefined) {
      pending.get(msg.replyTo)?.(msg);
      pending.delete(msg.replyTo);
      return;
    }
    events.push(msg);
    for (const w of [...waiters]) {
      if (w.predicate(msg)) {
        waiters.splice(waiters.indexOf(w), 1);
        w.resolve(msg);
      }
    }
  });
  return {
    ws,
    events,
    opened: new Promise((resolve) => ws.once('open', resolve)),
    closed: new Promise((resolve) => ws.once('close', (code) => resolve(code))),
    raw(type, params = {}) {
      const id = nextId++;
      ws.send(JSON.stringify({ id, type, ...params }));
      return new Promise((resolve) => pending.set(id, resolve));
    },
    async request(type, params) {
      const reply = await this.raw(type, params);
      if (!reply.ok) throw new Error(reply.error);
      return reply.data;
    },
    waitFor(predicate) {
      const hit = events.find(predicate);
      if (hit) return Promise.resolve(hit);
      return new Promise((resolve) => waiters.push({ predicate, resolve }));
    },
  };
}

const isResult = (m) => m.event?.type === 'sdk' && m.event.message.type === 'result';

let bridge, port, sdk, bridgeHome;

before(async () => {
  sdk = fakeSdk();
  bridgeHome = mkdtempSync(path.join(os.tmpdir(), 'bridge-test-'));
  bridge = createBridge({ token: TOKEN, sdk, home: os.tmpdir(), bridgeHome });
  port = await bridge.listen(0);
});

after(() => bridge.close());

test('rejects a wrong token and closes the socket', async () => {
  const c = client(port);
  await c.opened;
  const reply = await c.raw('hello', { token: 'nope' });
  assert.equal(reply.ok, false);
  assert.equal(await c.closed, 4401);
});

test('rejects requests sent before hello', async () => {
  const c = client(port);
  await c.opened;
  const reply = await c.raw('sessions.list');
  assert.equal(reply.error, 'unauthorized');
});

test('accepts a bearer header without hello', async () => {
  const c = client(port, { authorization: `Bearer ${TOKEN}` });
  await c.opened;
  const { sessions } = await c.request('sessions.list');
  assert.equal(sessions[0].summary, 'Old');
  c.ws.close();
});

test('streams a turn, then replays without superseded deltas', async () => {
  const c = client(port);
  await c.opened;
  await c.request('hello', { token: TOKEN });
  const opened = await c.request('session.open', {});
  assert.equal(opened.state, 'idle');
  assert.deepEqual(opened.history, []);
  assert.equal(opened.account.email, 'me@example.com');

  await c.request('user.message', { sessionId: opened.sessionId, text: 'hi' });
  await c.waitFor(isResult);
  const kinds = c.events.map((m) => (m.event.type === 'sdk' ? m.event.message.type : m.event.type));
  assert.deepEqual(kinds, ['user.echo', 'state', 'system', 'stream_event', 'assistant', 'result', 'state']);
  assert.deepEqual(c.events.map((m) => m.seq), [1, 2, 3, 4, 5, 6, 7]);
  c.ws.close();

  const c2 = client(port, { authorization: `Bearer ${TOKEN}` });
  await c2.opened;
  const again = await c2.request('session.open', { sessionId: opened.sessionId, afterSeq: 2 });
  assert.equal(again.gap, false);
  assert.equal(again.history, null);
  assert.deepEqual(again.events.map((e) => e.seq), [3, 5, 6, 7]);
  assert.equal(again.model, 'fake-model');
  c2.ws.close();
});

test('permission requests round-trip, including always-allow suggestions', async () => {
  const c = client(port, { authorization: `Bearer ${TOKEN}` });
  await c.opened;
  const { sessionId } = await c.request('session.open', {});
  await c.request('user.message', { sessionId, text: 'please write' });
  const ask = await c.waitFor((m) => m.event?.type === 'permission.request');
  assert.equal(ask.event.toolName, 'Write');
  assert.equal(bridge.live.get(sessionId).state, 'waiting_permission');

  const reopened = await c.request('session.open', { sessionId, afterSeq: 999 });
  assert.equal(reopened.pendingPermissions.length, 1);

  await c.request('permission.response', { sessionId, requestId: ask.event.requestId, decision: 'allow_always' });
  await c.waitFor(isResult);
  const answer = c.events.find((m) => m.event.type === 'sdk' && m.event.message.type === 'assistant');
  assert.equal(answer.event.message.message.content[0].text, 'allow');
  await assert.rejects(c.request('permission.response', { sessionId, requestId: ask.event.requestId, decision: 'deny' }));
  c.ws.close();
});

test('resuming loads the on-disk history and applies settings', async () => {
  const c = client(port, { authorization: `Bearer ${TOKEN}` });
  await c.opened;
  const sessionId = '11111111-1111-4111-8111-111111111111';
  const opened = await c.request('session.open', { sessionId, mode: 'plan', effort: 'high' });
  assert.equal(opened.history.length, 1);
  assert.equal(opened.mode, 'plan');
  assert.equal(opened.effort, 'high');

  await c.request('set.model', { sessionId, model: 'sonnet' });
  await c.request('set.mode', { sessionId, mode: 'acceptEdits' });
  await c.request('set.effort', { sessionId, effort: 'low' });
  assert.deepEqual(sdk.calls.setModel, ['sonnet']);
  assert.deepEqual(sdk.calls.setMode, ['acceptEdits']);
  assert.deepEqual(sdk.calls.effort, ['low']);
  await assert.rejects(c.request('set.mode', { sessionId, mode: 'nonsense' }));

  await c.request('session.delete', { sessionId });
  assert.deepEqual(sdk.calls.deleted, [sessionId]);
  assert.equal(bridge.live.has(sessionId), false);
  c.ws.close();
});

test('file attachments are saved and referenced by path; images become image blocks', async () => {
  const c = client(port, { authorization: `Bearer ${TOKEN}` });
  await c.opened;
  const { sessionId } = await c.request('session.open', {});
  await c.request('user.message', {
    sessionId,
    text: 'see',
    attachments: [
      { kind: 'file', name: '../../evil name.txt', data: Buffer.from('payload').toString('base64') },
      { kind: 'image', mediaType: 'image/png', data: 'AAAA' },
    ],
  });
  const reply = await c.waitFor((m) => m.event?.type === 'sdk' && m.event.message.type === 'assistant');
  const echoed = reply.event.message.message.content[0].text;
  const saved = /\[Attached file: (.+)\]/.exec(echoed)[1];
  assert.ok(saved.startsWith(path.join(bridgeHome, 'uploads', sessionId)), saved);
  assert.match(path.basename(saved), /^\d+-evil_name\.txt$/);
  assert.equal(readFileSync(saved, 'utf8'), 'payload');
  c.ws.close();
});

test('fs.list returns only folders and rejects relative mkdir', async () => {
  const c = client(port, { authorization: `Bearer ${TOKEN}` });
  await c.opened;
  const listing = await c.request('fs.list', { path: bridgeHome });
  assert.deepEqual(listing.dirs, ['uploads']);
  await assert.rejects(c.request('fs.mkdir', { path: 'relative/dir' }));
  c.ws.close();
});
