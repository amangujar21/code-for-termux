# Bridge protocol (v1)

JSON over a WebSocket on `127.0.0.1:<port>`. `GET /health` is the only unauthenticated endpoint.

Authenticate with an `Authorization: Bearer <token>` header, or by sending `hello` with `token` first.
A wrong token gets `{ok:false,error:"unauthorized"}` and close code 4401.

- Request: `{ "id": 1, "type": "<name>", ...params }`
- Reply: `{ "replyTo": 1, "ok": true, "data": {...} }` or `{ "replyTo": 1, "ok": false, "error": "..." }`
- Push: `{ "type": "event", "sessionId": "...", "seq": 12, "event": {...} }`

## Requests

| Type | Params | Notes |
| --- | --- | --- |
| `hello` | `token` | Bridge version, home directory |
| `bridge.status` | `fresh?` | Account, models, commands, Claude Code version. Cached for a minute unless `fresh`, which asks a new Claude Code process. A signed-out account has `tokenSource: "none"` |
| `sessions.list` | `dir?`, `limit?`, `offset?` | Rows carry `state` when the session is live |
| `session.open` | `sessionId?`, `cwd?`, `model?`, `mode?`, `effort?`, `afterSeq?` | Starts or resumes, subscribes, returns `history` (when `afterSeq` is 0 or events were lost) plus buffered `events` |
| `session.leave` / `session.stop` | `sessionId` | Unsubscribe / end the process |
| `user.message` | `sessionId`, `text`, `attachments[]` | Attachment: `{kind:"image",mediaType,data}` or `{kind:"file",name,data}` (base64) |
| `interrupt` | `sessionId` | |
| `permission.response` | `sessionId`, `requestId`, `decision` | `allow`, `allow_always`, `deny` |
| `set.model` / `set.mode` / `set.effort` | `sessionId`, value | Modes: `default`, `acceptEdits`, `plan`, `bypassPermissions` |
| `context.usage` / `mcp.status` / `commands.list` | `sessionId` | |
| `session.rename` / `session.delete` | `sessionId`, `title?` | |
| `fs.list` / `fs.mkdir` | `path` | Folders only. `fs.list` also returns `storage: {path, accessible}` for the phone's own storage (null off Android); it is not accessible until Termux has the storage permission |

## Events

`sdk` (an Agent SDK message, verbatim, in `message`), `user.echo`, `state`
(`idle` / `running` / `waiting_permission`, plus model, mode, effort), `permission.request`,
`permission.resolved`, `error`, `closed`.

Events are numbered per session. Streamed deltas are dropped from the replay buffer once the
complete assistant message has arrived.
