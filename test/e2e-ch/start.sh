#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

JAR="EnigmaEngine.jar"
LOG="logs/server.log"
PIDFILE="server.pid"

echo "============================================"
echo "        Cracked SMP 5 - EnigmaEngine"
echo "============================================"
echo ""

if [ ! -f "$JAR" ]; then
    echo "[FEHLER] $JAR nicht gefunden!"
    exit 1
fi

if [ ! -f "eula.txt" ]; then
    echo "eula=true" > eula.txt
fi

find_java() {
    local candidates=(
        "/usr/lib/jvm/java-25-openjdk-amd64/bin/java"
        "/usr/lib/jvm/jdk-25/bin/java"
        "/opt/java/jdk-25/bin/java"
    )

    for candidate in "${candidates[@]}"; do
        if [ -x "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done

    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"
        return 0
    fi

    if command -v java &>/dev/null; then
        command -v java
        return 0
    fi

    return 1
}

find_java_pid() {
    pgrep -f 'java.*EnigmaEngine\.jar' 2>/dev/null | head -1 || true
}

detect_free_mb() {
    local free_mb=8192

    if [ -r /proc/meminfo ]; then
        local free_kb
        free_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || true)

        if [ -n "$free_kb" ] && [ "$free_kb" -gt 0 ] 2>/dev/null; then
            free_mb=$((free_kb / 1024))
        fi
    fi

    echo "$free_mb"
}

stop_running_server() {
    local pid=""
    pid=$(find_java_pid)

    if [ -z "$pid" ]; then
        echo "Kein laufender Server gefunden - starte neuen."
        rm -f "$PIDFILE"
        return 0
    fi

    echo "Server laeuft bereits (Java PID=$pid). Wird heruntergefahren..."

    kill -TERM "$pid" 2>/dev/null || true

    local timeout=3
    local elapsed=0

    while [ "$elapsed" -lt "$timeout" ]; do

        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi

        sleep 1
        elapsed=$((elapsed + 1))

        printf "\r  Warte auf Shutdown... %ds/%ds" "$elapsed" "$timeout"
    done

    echo ""

    if kill -0 "$pid" 2>/dev/null; then
        echo "Timeout nach ${timeout}s - erzwinge Kill (SIGKILL)."
        kill -9 "$pid" 2>/dev/null || true
        sleep 1
    fi

    rm -f "$PIDFILE"

    echo "Alter Server gestoppt."
    echo ""
}

stop_running_server

if ! JAVA="$(find_java)"; then
    echo "[FEHLER] Kein Java gefunden."
    exit 1
fi

FREEMB="$(detect_free_mb)"

HEAPMB=$((FREEMB - 2048))

if [ "$HEAPMB" -lt 4096 ]; then
    HEAPMB=4096
fi

JVM_ARGS=(
    "-Xms${HEAPMB}M"
    "-Xmx${HEAPMB}M"
    -XX:+UnlockExperimentalVMOptions
    -XX:+AlwaysPreTouch
    -XX:+DisableExplicitGC
    -XX:+ParallelRefProcEnabled
    -XX:+PerfDisableSharedMem
    -XX:+UseG1GC
    -XX:G1HeapRegionSize=8M
    -XX:G1HeapWastePercent=5
    -XX:G1MaxNewSizePercent=40
    -XX:G1MixedGCCountTarget=4
    -XX:G1MixedGCLiveThresholdPercent=90
    -XX:G1NewSizePercent=30
    -XX:G1RSetUpdatingPauseTimePercent=5
    -XX:G1ReservePercent=20
    -XX:InitiatingHeapOccupancyPercent=15
    -XX:MaxGCPauseMillis=200
    -XX:MaxTenuringThreshold=1
    -XX:SurvivorRatio=32
    -Dfile.encoding=UTF-8
    -Dsun.stdout.encoding=UTF-8
    -Dsun.stderr.encoding=UTF-8
    --add-modules=jdk.incubator.vector
    -Denigma.distributed.enabled=true
    -Denigma.distributed.role=WORLD_HOST
    -Denigma.distributed.worldHost=5.175.223.91
    -Denigma.distributed.worldHostPort=25560
    -Denigma.distributed.port=25560
)

echo "Java:          $JAVA"
echo "Freier RAM:    ${FREEMB}M"
echo "Heap:          ${HEAPMB}M"
echo ""

mkdir -p logs

echo "Server wird im Hintergrund gestartet..."

nohup "$JAVA" \
    "${JVM_ARGS[@]}" \
    -jar "$JAR" \
    --nogui >> "$LOG" 2>&1 &

JAVA_PID=$!

echo "$JAVA_PID" > "$PIDFILE"

disown "$JAVA_PID" 2>/dev/null || true

sleep 2

if ! kill -0 "$JAVA_PID" 2>/dev/null; then
    echo "[FEHLER] Java-Prozess wurde beendet."
    rm -f "$PIDFILE"
    echo ""
    echo "Letzte Logs:"
    tail -30 "$LOG"
    exit 1
fi

echo ""
echo "Server gestartet."
echo "Java PID:      $JAVA_PID"
echo "Log:           $LOG"
echo ""
echo "Live-Log wird angezeigt."
echo "Ctrl+C beendet NUR die Log-Anzeige."
echo "Der Server laeuft weiter."
echo ""

tail -f "$LOG"
