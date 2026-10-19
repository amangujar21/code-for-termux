# Code for Termux

An unofficial Android client for [Claude Code](https://claude.com/claude-code) running locally in
[Termux](https://termux.dev). Not affiliated with or endorsed by Anthropic.

```
Android app (Compose)  <->  ws://127.0.0.1:8765 + token  <->  bridge (Node, in Termux)  <->  Agent SDK  <->  claude
```

- `app/` Kotlin + Jetpack Compose app.
- `bridge/` Node server that wraps the Claude Agent SDK. See `bridge/protocol.md`.
- `scripts/setup-termux.sh` one-time Termux setup, which the app has Termux run during onboarding.

## Install on a phone

1. Install Termux from F-Droid or GitHub (not the Play Store build) and open it once so it finishes
   its own first-time install.
2. Install the APK, open it and tap **Set up**. Allow "Run commands in Termux", then tap
   "Use this folder" and "Allow" on the Termux folder that opens. The app installs everything else
   in Termux in the background and shows its progress.
3. Tap **Sign in**. Termux opens Claude's sign-in, then sends you back to the app.

The folder step lets the app turn on Termux's `allow-external-apps` setting, which Termux requires
before it accepts commands from another app. Any app you grant the Termux run-command permission
can then run commands in Termux, so grant it sparingly.

If automatic setup does not work on your phone, Advanced on the setup screen has a command to paste
into Termux instead.

Claude Code has no Android build. Setup downloads the official linux-arm64 binary and patches it to
load Termux's glibc. Scripts that rely on Termux's `LD_PRELOAD` shebang fix may not run from Claude's
Bash tool.

## Bundled novel-writing skills

Setup also installs a few skills and agents into Termux's `~/.claude` (sources in `bridge/claude/`):

- `/new-novel` interviews you about the premise, characters, style and structure, then creates the
  project folder with its story bible and outline. Put it in phone storage so Obsidian can open it.
- `/write-chapter [N] [premise]` writes the next chapter (or chapter N) in a fresh subagent, so the
  chat's context stays small. Each chapter is its own Markdown file, with a summary and fact-sheet
  update that the next chapter reads.
- `/revise-chapter N <notes>` and `/summarize-chapters` revise a chapter and backfill summaries.

Setup and app updates replace these files, so make lasting changes in `bridge/claude/`, not on the
phone. Copy the same folders into `~/.claude` to use them on a PC.

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
