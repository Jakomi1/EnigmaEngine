#!/usr/bin/env bash
# ============================================================================
# EnigmaEngine Production Stop Script
# Gracefully signals the server, flushes chunks, and terminates safely.
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PIDFILE="server.pid"

if [ ! -f "$PIDFILE" ]; then
    PID=$(pgrep -f 'java.*EnigmaEngine\.jar' 2>/dev/null | head -1 || true)
else
    PID=$(cat "$PIDFILE" 2>/dev/null || true)
fi

if [ -z "${PID:-}" ] || ! kill -0 "$PID" 2>/dev/null; then
    echo "[✓] Server is not running."
    rm -f "$PIDFILE"
    exit 0
fi

echo "[INFO] Gracefully shutting down EnigmaEngine (PID=$PID)..."
kill -TERM "$PID" 2>/dev/null || true

for i in $(seq 1 20); do
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "[✓] EnigmaEngine stopped cleanly."
        rm -f "$PIDFILE"
        exit 0
    fi
    sleep 1
    printf "\rWaiting for chunk flush... %ds" "$i"
done

echo ""
echo "[WARN] Server did not stop within 20s. Sending SIGKILL..."
kill -9 "$PID" 2>/dev/null || true
rm -f "$PIDFILE"
echo "[✓] Process terminated."
