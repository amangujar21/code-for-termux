#!/data/data/com.termux/files/usr/bin/bash
# One-time setup for the Android app's Termux backend.
#
# Served by the app during onboarding (which fills in the __PLACEHOLDERS__):
#   curl -s http://127.0.0.1:<port>/setup | bash
#
# Installs Node, Claude Code (official linux-arm64 binary run through Termux's
# glibc), and the bridge server into ~/.claude-bridge. Safe to re-run.
set -euo pipefail

TOKEN="__TOKEN__"
BRIDGE_PORT="__BRIDGE_PORT__"
SETUP_URL="__SETUP_URL__"
CLAUDE_VERSION="${CLAUDE_VERSION:-latest}"

BRIDGE_HOME="$HOME/.claude-bridge"
CLAUDE_DIR="$BRIDGE_HOME/claude"
GLIBC_LD="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"

say() { printf '\n\033[1;33m==>\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31mSetup failed:\033[0m %s\n' "$*" >&2; exit 1; }

[ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ] || die "This script must run inside Termux."
[ "$(uname -m)" = "aarch64" ] || die "Only 64-bit ARM phones are supported (found $(uname -m))."

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Fetch the bridge first: Android may stop the app while the slow steps below run.
say "Fetching the bridge from the app"
curl -fsS "$SETUP_URL/bridge.tgz" -o "$WORK/bridge.tgz" || die "Could not reach the app. Reopen its setup screen, copy the command again and retry."

say "Installing packages (this can take a few minutes)"
export DEBIAN_FRONTEND=noninteractive
pkg update -y -o Dpkg::Options::="--force-confold"
pkg install -y -o Dpkg::Options::="--force-confold" nodejs-lts git ripgrep curl tar glibc-repo
pkg install -y -o Dpkg::Options::="--force-confold" glibc-runner patchelf
[ -x "$GLIBC_LD" ] || die "glibc loader not found at $GLIBC_LD after installing glibc-runner."

mkdir -p "$BRIDGE_HOME" "$CLAUDE_DIR"
chmod 700 "$BRIDGE_HOME"

say "Downloading Claude Code ($CLAUDE_VERSION)"
(
  cd "$WORK"
  # The published package is tagged linux/arm64, so fetch the tarball directly
  # instead of letting npm reject it on Android.
  npm pack --silent "@anthropic-ai/claude-code-linux-arm64@$CLAUDE_VERSION" >/dev/null
  tar -xzf anthropic-ai-claude-code-linux-arm64-*.tgz
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

say "Starting the bridge"
"$BRIDGE_HOME/start-bridge.sh"
sleep 2
if curl -fsS "http://127.0.0.1:$BRIDGE_PORT/health" >/dev/null; then
  curl -fsS -X POST "$SETUP_URL/done" >/dev/null 2>&1 || true
  printf '\n\033[1;32mAll set.\033[0m\n'
  if [ ! -f "$HOME/.claude/.credentials.json" ]; then
    printf 'Last step: run \033[1mclaude\033[0m here once and sign in, then go back to the app.\n'
  else
    printf 'Go back to the app.\n'
  fi
else
  die "The bridge did not start. See $BRIDGE_HOME/bridge.log"
fi
