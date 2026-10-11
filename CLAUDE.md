# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An unofficial Android client for Claude Code running locally in Termux. Two halves that ship together:

```
Android app (Kotlin/Compose)  <->  ws://127.0.0.1:8765 + token  <->  bridge (Node, in Termux)  <->  Agent SDK  <->  claude
```

- `app/` the Android app.
- `bridge/` Node WebSocket server wrapping `@anthropic-ai/claude-agent-sdk`. Wire format is in `bridge/protocol.md`.
- `scripts/setup-termux.sh` one-time Termux install, which the app has Termux run during onboarding.

## Commands

Development is on Windows; use `gradlew.bat` (or `./gradlew` from Git Bash). Needs JDK 17 and `local.properties` with `sdk.dir`.

```
gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
gradlew assembleRelease               # minified; signed via keystore.properties, else the debug key
gradlew testDebugUnitTest
gradlew testDebugUnitTest --tests "dev.amang.claudetermux.ChatReducerTest"
gradlew testDebugUnitTest --tests "*.MarkdownTest.someMethod"

cd bridge && npm install && npm test  # node --test, uses a fake SDK
node --test --test-name-pattern "<name>" bridge/test/bridge.test.js
node bridge/tools/smoke.js            # end-to-end against the real Claude Code on this machine (CLAUDE_BIN to override)
```

There is no linter configured.

The release APK is what gets installed on the phone. Updates must be signed with the same key (`release.jks` / `keystore.properties`, both gitignored), so do not regenerate it.

## How the pieces fit

**The bridge is packaged inside the APK.** `app/build.gradle.kts` tars `bridge/{server.js,session.js,package.json,package-lock.json,start-bridge.sh}` into `bridge.tgz` and copies `scripts/setup-termux.sh` beside it as generated assets on every build. Editing those files changes the APK; a new bridge file must be added to the `packBridge` include list or it will not reach the phone. Keep the three version strings in step: `versionName` in `app/build.gradle.kts`, `version` in `bridge/package.json`, `BRIDGE_VERSION` in `bridge/server.js`.

**Onboarding installs everything from the app** (`ui/onboarding/OnboardingScreen.kt`, driven by `OnboardingUi.action`, which picks the one thing the screen is waiting on):

1. `TermuxLauncher` sends Termux `RUN_COMMAND` intents. That needs the `com.termux.permission.RUN_COMMAND` runtime permission and Termux's `allow-external-apps = true`.
2. `TermuxAccess` turns that setting on by editing `~/.termux/termux.properties` through Termux's documents provider, after the user confirms the Termux folder in the system picker.
3. `SetupServer` is a loopback HTTP server (random port, random secret path) that serves the setup script with `__TOKEN__`, `__BRIDGE_PORT__`, `__SETUP_URL__`, `__MODE__` filled in, serves `bridge.tgz`, and receives `progress/<step>[/<percent>]`, `log` (the latest output, every two seconds), `failed` (log tail) and `done` callbacks from the script. The app runs `curl -s <url>/setup | bash` in Termux in the background.
4. The script installs Node, downloads the official linux-arm64 Claude Code binary, `patchelf`s it onto Termux's glibc, installs the bridge into `~/.claude-bridge`, writes `config.json` (token, port, `claudePath`) and starts the bridge.

Adding a setup step means touching both ends: a `progress <name>` call in the script and its entry in `setupSteps` (`OnboardingScreen.kt`), which holds the label and the step's share of the progress bar.

**App updates replace the bridge.** When the connected bridge's version differs from the app's `versionName` (`Hello.outdated`), the sessions screen and onboarding offer Update, which runs `<url>/update`: the same script with `MODE=bridge`, skipping the package and Claude Code installs. A bridge change therefore only reaches an existing install if the version strings are bumped.

**Sign-in happens in Termux** (`TermuxLauncher.openSignIn` runs `claude auth login`), from onboarding, from `/login`, or from the card the chat shows when a turn fails with `authentication_failed`. Claude Code rereads its credentials every turn, so open sessions keep working afterwards.

**Phone storage.** Sessions can only run in `/storage/emulated/0` after Termux has Android's storage permission. `fs.list` reports whether it does, and the folder picker has Termux ask for it (`TermuxLauncher.openStorageSetup` runs `termux-setup-storage`).

**Runtime.** `App` owns the singletons: `Settings` (DataStore; holds the pairing token, endpoint, onboarding flags), `BridgeClient` and `TurnTracker`. `BridgeClient` keeps one auto-reconnecting WebSocket, correlates requests by `id`/`replyTo`, and exposes `state` plus a shared `events` flow. `Protocol.kt` holds the serializable message types and must match `bridge/protocol.md`.

**Sessions outlive connections.** In the bridge, `LiveSession` (`session.js`) keeps a streaming Agent SDK `query` alive regardless of whether the app is connected, and appends everything the app renders to a per-session sequenced event buffer. `session.open` with `afterSeq` replays what a reconnecting client missed (or full `history` if events were lost). On the app side `ChatReducer` is a pure fold of history plus live events into `ChatState`; it tracks `lastSeq` and is where rendering logic for SDK messages belongs. `ChatViewModel` wires it to the bridge. SDK messages cross the bridge verbatim inside `sdk` events, so new Claude Code message shapes are handled in the reducer, not the bridge.

`createBridge({ sdk, token, ... })` takes the SDK module as a parameter; tests inject a fake one.

## Things that are easy to get wrong

- **A signed-out Claude Code still reports an account**: `{tokenSource: "none", apiProvider: "firstParty"}`. `AccountInfo.signedIn` has to treat `"none"` as absent, or onboarding skips the sign-in step.
- **Play Store Termux does not work.** It cannot take commands from other apps. Only the F-Droid or GitHub build does; `TermuxLauncher.supportsRunCommand` detects this.
- **Android stops showing the permission prompt** after two refusals; the request then fails instantly. Onboarding detects the instant failure and sends the user to the app's system settings page instead.
- **The folder picker must be opened with Termux's root URI** (`DocumentsContract.buildRootUri`), not a document URI: Termux's provider cannot resolve a document's path, and the picker silently falls back to phone storage.
- **`LD_PRELOAD` must be unset** for anything that runs the patched `claude` binary (the `claude` wrapper and `start-bridge.sh` both do this); termux-exec's preload is a bionic library and crashes glibc programs.
- Shell scripts must keep LF endings (`.gitattributes` handles this; `SetupServer` also strips CRs when serving).
- The setup script only supports aarch64. On the x86_64 emulator it stops at that check, which is still useful: it exercises the permission, folder, `RUN_COMMAND` and callback path end to end.

## Testing on the emulator

For chat and session work, skip Termux: run the bridge on the PC (`CLAUDE_BRIDGE_TOKEN=<16+ chars> node bridge/server.js`), `adb reverse tcp:8765 tcp:8765`, and enter the token under Advanced on the setup screen.

For onboarding work, install an x86_64 Termux APK from the Termux GitHub releases, open it once so it bootstraps, then install the app. `adb` is not on PATH; it is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. From Git Bash, set `MSYS_NO_PATHCONV=1` before `adb shell` commands that contain device paths.
