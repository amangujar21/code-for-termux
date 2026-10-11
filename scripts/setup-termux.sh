#!/data/data/com.termux/files/usr/bin/bash
# One-time setup for the Android app's Termux backend.
#
# Served by the app during onboarding (which fills in the __PLACEHOLDERS__) and
# normally started by the app itself through Termux's RUN_COMMAND intent:
#   curl -s http://127.0.0.1:<port>/setup | bash
#
# Installs Node, Claude Code (official linux-arm64 binary run through Termux's
# glibc), and the bridge server into ~/.claude-bridge. Safe to re-run.
set -euo pipefail

TOKEN="__TOKEN__"
BRIDGE_PORT="__BRIDGE_PORT__"
SETUP_URL="__SETUP_URL__"
# "bridge" only replaces the bridge, which is all an app update needs. Anything else installs everything.
MODE="__MODE__"
CLAUDE_VERSION="${CLAUDE_VERSION:-latest}"

BRIDGE_HOME="$HOME/.claude-bridge"
CLAUDE_DIR="$BRIDGE_HOME/claude"
GLIBC_LD="${PREFIX:-}/glibc/lib/ld-linux-aarch64.so.1"
LOG="$BRIDGE_HOME/setup.log"
LOCK="$BRIDGE_HOME/setup.pid"

say() { printf '\n\033[1;33m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31mSetup failed:\033[0m %s\n' "$*" >&2; exit 1; }
# Tells the app's setup screen which step is running, and optionally how many percent of it are done.
progress() { curl -fsS -m 5 -X POST "$SETUP_URL/progress/$1${2:+/$2}" >/dev/null 2>&1 || true; }

mkdir -p "$BRIDGE_HOME"
chmod 700 "$BRIDGE_HOME"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK")" 2>/dev/null; then
  echo "Setup is already running."
  progress running
  exit 0
fi
echo $$ > "$LOCK"
exec > >(tee "$LOG") 2>&1

# Sends the app the latest output every couple of seconds, so its setup screen shows what is happening.
(
  while sleep 2 && kill -0 $$ 2>/dev/null; do
    tail -c 2000 "$LOG" | tr '\r' '\n' | sed 's/\x1b\[[0-9;]*m//g' | tr -cd '\11\12\40-\176' | grep -v '^[[:space:]]*$' | tail -n 6 |
      curl -fsS -m 5 -X POST --data-binary @- "$SETUP_URL/log" >/dev/null 2>&1 || true
  done
) &
REPORTER=$!

WORK="$(mktemp -d)"
finish() {
  code=$?
  kill "$REPORTER" 2>/dev/null || true
  rm -rf "$WORK"
  rm -f "$LOCK"
  if [ "$code" -ne 0 ]; then
    sleep 1 # let tee catch up
    tail -n 25 "$LOG" | sed 's/\x1b\[[0-9;]*m//g' | tr -cd '\11\12\40-\176' |
      curl -fsS -m 5 -X POST --data-binary @- "$SETUP_URL/failed" >/dev/null 2>&1 || true
  fi
}
trap finish EXIT

[ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ] || die "This script must run inside Termux."
[ "$(uname -m)" = "aarch64" ] || die "Only 64-bit ARM phones are supported (found $(uname -m))."

# Fetch the bridge first: Android may stop the app while the slow steps below run.
say "Fetching the bridge from the app"
curl -fsS "$SETUP_URL/bridge.tgz" -o "$WORK/bridge.tgz" || die "Could not reach the app. Reopen its setup screen and retry."

# Keep Android from suspending Termux during the long steps.
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true

# Downloads $1 to $2, telling the app how far along it is.
download() {
  local total pid pct last=-1
  # The registry leaves the size out of HEAD replies, so ask for one byte and read the total from its range.
  total="$(curl -fsSL -m 20 -r 0-0 -o /dev/null -D - "$1" | tr -d '\r' | awk -F/ 'tolower($0) ~ /^content-range:/ { n = $2 } END { print n + 0 }')" || total=0
  curl -fsSL --retry 3 "$1" -o "$2" &
  pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    sleep 2
    if [ "${total:-0}" -gt 0 ] && [ -f "$2" ]; then
      pct=$(($(stat -c %s "$2") * 100 / total))
      if [ "$pct" != "$last" ]; then
        last=$pct
        echo "Downloaded $pct%"
        progress claude "$pct"
      fi
    fi
  done
  wait "$pid"
}

install_packages() {
  export DEBIAN_FRONTEND=noninteractive
  progress upgrade
  say "Updating Termux packages (this can take a few minutes)"
  # Upgrade first: Termux does not support installing new packages onto an outdated base.
  pkg upgrade -y -o Dpkg::Options::="--force-confold"
  progress packages
  say "Installing Node.js and tools"
  pkg install -y -o Dpkg::Options::="--force-confold" nodejs-lts git ripgrep curl tar glibc-repo
  progress glibc
  say "Installing glibc"
  pkg install -y -o Dpkg::Options::="--force-confold" glibc-runner patchelf
  [ -x "$GLIBC_LD" ] || die "glibc loader not found at $GLIBC_LD after installing glibc-runner."
}

install_claude() {
  local pkg="@anthropic-ai/claude-code-linux-arm64@$CLAUDE_VERSION" url
  mkdir -p "$CLAUDE_DIR"

  progress claude 0
  say "Downloading Claude Code ($CLAUDE_VERSION)"
  # The published package is tagged linux/arm64, so fetch the tarball directly
  # instead of letting npm reject it on Android.
  url="$(npm view "$pkg" dist.tarball 2>/dev/null | tail -n 1)" || url=""
  (
    cd "$WORK"
    # curl can report how far the download is; npm pack is the fallback.
    case "$url" in
      https://*) download "$url" claude.tgz || rm -f claude.tgz ;;
    esac
    if [ ! -f claude.tgz ]; then
      npm pack --silent "$pkg" >/dev/null
      mv anthropic-ai-claude-code-linux-arm64-*.tgz claude.tgz
    fi
    progress unpack
    say "Unpacking Claude Code"
    tar -xzf claude.tgz
  )
  BIN_SRC="$(find "$WORK/package" -type f -size +20M | head -n 1)"
  [ -n "$BIN_SRC" ] || die "Could not find the Claude Code binary inside the npm package."
  install -m 755 "$BIN_SRC" "$CLAUDE_DIR/claude.bin"

  say "Patching the binary to use Termux's glibc"
  patchelf --set-interpreter "$GLIBC_LD" "$CLAUDE_DIR/claude.bin"

  # termux-exec's LD_PRELOAD is a bionic library and crashes glibc programs.
  cat > "$PREFIX/bin/claude" <<EOF
#!$PREFIX/bin/sh
unset LD_PRELOAD
export DISABLE_AUTOUPDATER=1
exec "$CLAUDE_DIR/claude.bin" "\$@"
EOF
  chmod 755 "$PREFIX/bin/claude"

  if ! CLAUDE_REPORTED="$(claude --version 2>&1)"; then
    die "Claude Code does not start on this device: $CLAUDE_REPORTED"
  fi
  say "Claude Code works: $CLAUDE_REPORTED"
}

if [ "$MODE" = bridge ] && [ -x "$CLAUDE_DIR/claude.bin" ] && command -v node >/dev/null 2>&1; then
  say "Updating the bridge"
else
  install_packages
  install_claude
fi

progress bridge
say "Installing the bridge"
rm -rf "$BRIDGE_HOME/app.new"
mkdir -p "$BRIDGE_HOME/app.new"
tar -xzf "$WORK/bridge.tgz" -C "$BRIDGE_HOME/app.new"
# The SDK's optional native binaries have no Android build; we point it at the patched one instead.
(cd "$BRIDGE_HOME/app.new" && npm install --omit=dev --omit=optional --no-audit --no-fund --silent)
rm -rf "$BRIDGE_HOME/app.old"
[ -d "$BRIDGE_HOME/app" ] && mv "$BRIDGE_HOME/app" "$BRIDGE_HOME/app.old"
mv "$BRIDGE_HOME/app.new" "$BRIDGE_HOME/app"
rm -rf "$BRIDGE_HOME/app.old"
install -m 755 "$BRIDGE_HOME/app/start-bridge.sh" "$BRIDGE_HOME/start-bridge.sh"

umask 077
cat > "$BRIDGE_HOME/config.json" <<EOF
{
  "token": "$TOKEN",
  "port": $BRIDGE_PORT,
  "claudePath": "$CLAUDE_DIR/claude.bin"
}
EOF

say "Allowing the app to start the bridge"
mkdir -p "$HOME/.termux"
PROPS="$HOME/.termux/termux.properties"
touch "$PROPS"
if grep -qE '^\s*#?\s*allow-external-apps\s*=' "$PROPS"; then
  sed -i -E 's/^\s*#?\s*allow-external-apps\s*=.*/allow-external-apps = true/' "$PROPS"
else
  printf '\nallow-external-apps = true\n' >> "$PROPS"
fi
termux-reload-settings 2>/dev/null || true

progress starting
say "Starting the bridge"
# Restart so a bridge left over from an earlier setup picks up the new token.
"$BRIDGE_HOME/start-bridge.sh" --restart
sleep 3
if curl -fsS "http://127.0.0.1:$BRIDGE_PORT/health" >/dev/null; then
  curl -fsS -X POST "$SETUP_URL/done" >/dev/null 2>&1 || true
  printf '\n\033[1;32mAll set.\033[0m\n'
  if [ ! -f "$HOME/.claude/.credentials.json" ]; then
    printf 'Last step: go back to the app and tap Sign in.\n'
  else
    printf 'Go back to the app.\n'
  fi
else
  die "The bridge did not start. See $BRIDGE_HOME/bridge.log"
fi
