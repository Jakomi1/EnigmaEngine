#!/usr/bin/env bash
# ============================================================================
# EnigmaEngine Production Start Script — Multicore Region Architecture
#
# Optimized Topology:
#   - 7 CPU Cores (pinned via taskset)
#   - 16 GB System Memory (12 GB Heap, G1GC Region-Tuned)
#   - Folia AFFINITY Scheduler + SIMD Vector Optimizations
#
# Environment Overrides (optional):
#   ENIGMA_CPUSET="0-6"       (default: 0-6)
#   ENIGMA_HEAP_MIN="4G"      (default: 4G)
#   ENIGMA_HEAP_MAX="12G"     (default: 12G)
#   ENIGMA_PORT="25605"       (default: from server.properties)
#   JAVA_BIN="/path/to/java"  (default: auto-detected Java 25)
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ── Locate Server JAR ──
JAR="EnigmaEngine.jar"
if [ ! -f "$JAR" ]; then
    if [ -f "../serverJar/EnigmaEngine.jar" ]; then
        echo "[INFO] Copying server JAR from ../serverJar/EnigmaEngine.jar..."
        cp "../serverJar/EnigmaEngine.jar" "$JAR"
    elif [ -f "../../serverJar/EnigmaEngine.jar" ]; then
        echo "[INFO] Copying server JAR from ../../serverJar/EnigmaEngine.jar..."
        cp "../../serverJar/EnigmaEngine.jar" "$JAR"
    else
        echo "[ERROR] $JAR not found in $(pwd) or ../serverJar/!"
        exit 1
    fi
fi

# ── Auto-accept EULA if not present ──
if [ ! -f "eula.txt" ]; then
    echo "eula=true" > eula.txt
fi

# ── Locate Java 25 Runtime ──
JAVA="${JAVA_BIN:-}"
if [ -z "$JAVA" ]; then
    if [ -x "/usr/lib/jvm/java-25-openjdk-amd64/bin/java" ]; then
        JAVA="/usr/lib/jvm/java-25-openjdk-amd64/bin/java"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA="$JAVA_HOME/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA="$(command -v java)"
    else
        echo "[ERROR] No Java executable found!"
        exit 1
    fi
fi

JAVA_VER=$("$JAVA" -version 2>&1 | head -1)
echo "[✓] Using Java: $JAVA_VER ($JAVA)"

# ── CPU Affinity & Resource Constraints ──
CPUSET="${ENIGMA_CPUSET:-0-6}"
echo "[✓] CPU Affinity: cores $CPUSET"

HEAP_MIN="${ENIGMA_HEAP_MIN:-4G}"
HEAP_MAX="${ENIGMA_HEAP_MAX:-12G}"
echo "[✓] Memory Heap:  $HEAP_MIN - $HEAP_MAX"

# ── Stop Existing Instance if running ──
PIDFILE="server.pid"
if [ -f "$PIDFILE" ]; then
    OLD_PID=$(cat "$PIDFILE" 2>/dev/null || true)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[!] Existing server process found (PID=$OLD_PID). Stopping gracefully..."
        kill -TERM "$OLD_PID" 2>/dev/null || true
        for i in $(seq 1 10); do
            if ! kill -0 "$OLD_PID" 2>/dev/null; then break; fi
            sleep 1
        done
        if kill -0 "$OLD_PID" 2>/dev/null; then
            kill -9 "$OLD_PID" 2>/dev/null || true
        fi
    fi
    rm -f "$PIDFILE"
fi

# ── JVM Flags ──
JVM_ARGS=(
    "-Xms${HEAP_MIN}"
    "-Xmx${HEAP_MAX}"
    -XX:+UseG1GC
    -XX:+UnlockExperimentalVMOptions
    -XX:G1HeapRegionSize=16M
    -XX:MaxGCPauseMillis=130
    -XX:+DisableExplicitGC
    -XX:+ParallelRefProcEnabled
    --add-modules=jdk.incubator.vector
    -Dfile.encoding=UTF-8
    -Dsun.stdout.encoding=UTF-8
    -Dsun.stderr.encoding=UTF-8
    -Denigma.serverName="EnigmaEngine Production"
)

mkdir -p logs

echo "[INFO] Starting EnigmaEngine..."
nohup taskset -c "$CPUSET" \
    "$JAVA" \
    "${JVM_ARGS[@]}" \
    -jar "$JAR" \
    nogui >> logs/server.log 2>&1 < /dev/null &

NEW_PID=$!
echo "$NEW_PID" > "$PIDFILE"

sleep 3
if kill -0 "$NEW_PID" 2>/dev/null; then
    echo "[✓] EnigmaEngine started successfully!"
    echo "    PID:      $NEW_PID"
    echo "    Affinity: cores $CPUSET"
    echo "    Logs:     logs/server.log"
else
    echo "[ERROR] Server died on startup! Check logs/server.log"
    tail -n 30 logs/server.log
    exit 1
fi
