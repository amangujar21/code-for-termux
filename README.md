# Code for Termux

An unofficial Android client for [Claude Code](https://claude.com/claude-code) running locally in
[Termux](https://termux.dev). Not affiliated with or endorsed by Anthropic.

```
Android app (Compose)  <->  ws://127.0.0.1:8765 + token  <->  bridge (Node, in Termux)  <->  Agent SDK  <->  claude
```

- `app/` Kotlin + Jetpack Compose app.
- `bridge/` Node server that wraps the Claude Agent SDK. See `bridge/protocol.md`.
- `scripts/setup-termux.sh` one-time Termux setup, served to Termux by the app during onboarding.

## Install on a phone

1. Install Termux from F-Droid or GitHub (not the Play Store build).
2. Install the APK and open it. Copy the setup command it shows, paste it into Termux, and wait for "All set".
3. Run `claude` once in Termux and sign in.
4. Back in the app, grant "Run commands in Termux" and tap Continue.

The setup turns on Termux's `allow-external-apps`. Any app you grant the Termux run-command
permission can then run commands in Termux, so grant it sparingly.

Claude Code has no Android build. Setup downloads the official linux-arm64 binary and patches it to
load Termux's glibc. Scripts that rely on Termux's `LD_PRELOAD` shebang fix may not run from Claude's
Bash tool.

## What the app does

Session list by project, new session in any Termux folder, streaming chat with markdown, tool cards,
permission prompts and modes, model and effort picker, slash-command popup, image/file attachments,
dictation, read-aloud, and a notification when a turn finishes or needs permission.

`/model`, `/clear`, `/resume`, `/permissions`, `/context`, `/mcp` and `/rename` are implemented
natively. Other commands are passed to Claude Code; ones it marks terminal-only are hidden.

## Build

Needs JDK 17 and the Android SDK (`local.properties` with `sdk.dir`).

```
gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
gradlew testDebugUnitTest
cd bridge && npm install && npm test
node bridge/tools/smoke.js       # end-to-end against the Claude Code on this machine
```

`assembleRelease` signs with `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) when present, otherwise with the debug key. Keep the keystore: updates must be signed
with the same key.

To develop against an emulator, run the bridge on the PC
(`CLAUDE_BRIDGE_TOKEN=<16+ chars> node bridge/server.js`), run `adb reverse tcp:8765 tcp:8765`, and
enter the token under Advanced on the setup screen.
