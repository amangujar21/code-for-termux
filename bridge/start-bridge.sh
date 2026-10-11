#!/data/data/com.termux/files/usr/bin/sh
# Starts the bridge in the background if it is not already running.
# Called by setup and by the app (Termux RUN_COMMAND intent).
BRIDGE_HOME="$HOME/.claude-bridge"
PORT="$(sed -n 's/.*"port"[^0-9]*\([0-9]*\).*/\1/p' "$BRIDGE_HOME/config.json" 2>/dev/null)"
PORT="${PORT:-8765}"

if curl -fsS -m 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  [ "$1" = "--restart" ] || exit 0
  [ -f "$BRIDGE_HOME/bridge.pid" ] && kill "$(cat "$BRIDGE_HOME/bridge.pid")" 2>/dev/null
  sleep 1
fi

# Keep Android from suspending Termux while Claude is working.
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock

cd "$BRIDGE_HOME/app" || exit 1
[ -f "$BRIDGE_HOME/bridge.log" ] && mv -f "$BRIDGE_HOME/bridge.log" "$BRIDGE_HOME/bridge.log.1"
# The bridge spawns the glibc-patched claude binary, which cannot load termux-exec.
nohup env -u LD_PRELOAD node server.js >"$BRIDGE_HOME/bridge.log" 2>&1 &
echo $! >"$BRIDGE_HOME/bridge.pid"
