#!/usr/bin/env node
// End-to-end check against the real Claude Code on this machine:
//   node tools/smoke.js
// Starts a bridge in-process, then drives it the way the app does.
import { randomBytes } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import WebSocket from 'ws';
import { createBridge } from '../server.js';

const sdk = await import('@anthropic-ai/claude-agent-sdk');
const token = randomBytes(24).toString('hex');
const bridge = createBridge({ token, sdk, claudePath: process.env.CLAUDE_BIN });
const port = await bridge.listen(0);
const cwd = mkdtempSync(path.join(os.tmpdir(), 'bridge-smoke-'));

function connect() {
  const ws = new WebSocket(`ws://127.0.0.1:${port}`);
  const pending = new Map();
  const listeners = new Set();
  let nextId = 1;
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.replyTo !== undefined) {
      const p = pending.get(msg.replyTo);
      pending.delete(msg.replyTo);
      msg.ok ? p.resolve(msg.data) : p.reject(new Error(msg.error));
    } else {
      for (const l of listeners) l(msg);
    }
  });
  return {
    ws,
    opened: new Promise((resolve, reject) => ws.once('open', resolve).once('error', reject)),
    request(type, params = {}) {
      const id = nextId++;
      ws.send(JSON.stringify({ id, type, ...params }));
      return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
    },
    on: (l) => listeners.add(l),
    waitFor(predicate, timeoutMs = 180_000) {
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Timed out waiting for event')), timeoutMs);
        const l = (msg) => {
          if (predicate(msg)) {
            clearTimeout(timer);
            listeners.delete(l);
            resolve(msg);
          }
        };
        listeners.add(l);
      });
    },
  };
}

const step = (name, detail = '') => console.log(`ok  ${name}${detail ? ` — ${detail}` : ''}`);
const isResult = (m) => m.event?.type === 'sdk' && m.event.message.type === 'result';

try {
  const c = connect();
  await c.opened;
  const hello = await c.request('hello', { token });
  step('hello', `bridge ${hello.bridgeVersion}, node ${hello.node}`);

  const status = await c.request('bridge.status');
  step('bridge.status', `Claude Code ${status.claudeCodeVersion}, ${status.models.length} models, ${status.commands.length} commands, account ${status.account.email ? 'logged in' : JSON.stringify(status.account)}`);

  const opened = await c.request('session.open', { cwd });
  const sessionId = opened.sessionId;
  step('session.open', `model=${opened.model} mode=${opened.mode}`);

  let deltas = 0;
  c.on((m) => {
    if (m.event?.type === 'sdk' && m.event.message.type === 'stream_event') deltas++;
    if (m.event?.type === 'permission.request') {
      console.log(`    permission requested: ${m.event.toolName} ${JSON.stringify(m.event.input).slice(0, 100)}`);
      c.request('permission.response', { sessionId, requestId: m.event.requestId, decision: 'allow' });
    }
  });

  let done = c.waitFor(isResult);
  await c.request('user.message', { sessionId, text: 'Reply with exactly the word: pong' });
  let result = (await done).event.message;
  step('streamed turn', `${deltas} stream events, result="${String(result.result).slice(0, 40)}"`);

  const usage = await c.request('context.usage', { sessionId });
  step('context.usage', `${usage.totalTokens}/${usage.maxTokens} tokens`);

  const other = status.models.find((m) => m.value !== opened.model && /haiku/i.test(m.value + m.displayName)) ?? status.models[0];
  await c.request('set.model', { sessionId, model: other.value });
  step('set.model', other.value);

  done = c.waitFor(isResult);
  await c.request('user.message', { sessionId, text: 'Use the Write tool to create a file named hello.txt in the current directory containing exactly: hi' });
  result = (await done).event.message;
  const file = path.join(cwd, 'hello.txt');
  step('permission round-trip', existsSync(file) ? `hello.txt = ${JSON.stringify(readFileSync(file, 'utf8'))}` : 'FILE MISSING');

  const seqBefore = (await c.request('session.open', { sessionId, afterSeq: 0 })).seq;
  c.ws.close();
  const c2 = connect();
  await c2.opened;
  await c2.request('hello', { token });
  const replay = await c2.request('session.open', { sessionId, afterSeq: 2 });
  step('reconnect + replay', `${replay.events.length} events after seq 2 (head ${seqBefore}), gap=${replay.gap}`);

  const list = await c2.request('sessions.list', { dir: cwd });
  step('sessions.list', `${list.sessions.length} session(s) in temp project: ${list.sessions.map((s) => s.summary).join(' | ')}`);

  const dirs = await c2.request('fs.list', {});
  step('fs.list', `${dirs.dirs.length} folders under ${dirs.path}`);

  await c2.request('session.delete', { sessionId });
  step('session.delete');
  c2.ws.close();
} catch (err) {
  console.error('FAILED:', err);
  process.exitCode = 1;
} finally {
  await bridge.close();
}
