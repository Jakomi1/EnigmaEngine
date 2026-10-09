#!/usr/bin/env bash
# ============================================================================
# EnigmaEngine Live Monitoring Dashboard
#
# Polls the server via RCON and system metrics to display:
#   - TPS (from /enigma tps)
#   - CPU per-core usage
#   - Memory usage
#   - Region count
#   - Player count
#
# Usage: ./monitor.sh [interval_seconds]
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

INTERVAL="${1:-5}"
RCON_PORT=25615
RCON_PASS="enigma_bench_2026"
PIDFILE="server.pid"
LOG_FILE="logs/monitor.csv"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Check if mcrcon is available, if not use simple netcat approach
RCON_CMD=""
if command -v mcrcon &>/dev/null; then
    RCON_CMD="mcrcon"
fi

rcon_exec() {
    if [ -n "$RCON_CMD" ]; then
        mcrcon -H 127.0.0.1 -P "$RCON_PORT" -p "$RCON_PASS" "$1" 2>/dev/null || echo "RCON_ERROR"
    else
        echo "RCON_UNAVAILABLE"
    fi
}

get_server_pid() {
    if [ -f "$PIDFILE" ]; then
        cat "$PIDFILE"
    else
        pgrep -f 'java.*EnigmaEngine\.jar' 2>/dev/null | head -1 || echo ""
    fi
}

# Initialize CSV log
echo "timestamp,cpu_percent,mem_used_mb,mem_total_mb,tps,mspt,regions,players,threads" > "$LOG_FILE"

echo -e "${CYAN}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║    EnigmaEngine Live Performance Monitor        ║${NC}"
echo -e "${CYAN}║    Interval: ${INTERVAL}s | Cores: 0-6 (7 pinned)       ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════════╝${NC}"
echo ""

while true; do
    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    PID=$(get_server_pid)

    if [ -z "$PID" ] || ! kill -0 "$PID" 2>/dev/null; then
        echo -e "${RED}[$(date '+%H:%M:%S')] Server not running!${NC}"
        sleep "$INTERVAL"
        continue
    fi

    # CPU usage for the Java process (across all threads, on cores 0-6)
    CPU_PERCENT=$(ps -p "$PID" -o %cpu= 2>/dev/null | tr -d ' ' || echo "0")
    
    # Per-core CPU from /proc/stat (cores 0-6 only)
    CORE_USAGE=""
    for i in $(seq 0 6); do
        USAGE=$(awk "/^cpu$i / {idle=\$5; total=0; for(j=2;j<=NF;j++) total+=\$j; printf \"%.0f\", 100*(1-idle/total)}" /proc/stat 2>/dev/null || echo "?")
        CORE_USAGE="${CORE_USAGE}C${i}:${USAGE}% "
    done

    # Memory usage
    MEM_INFO=$(ps -p "$PID" -o rss= 2>/dev/null | tr -d ' ' || echo "0")
    MEM_MB=$((MEM_INFO / 1024))
    MEM_TOTAL=$(free -m | awk '/Mem:/ {print $2}')
    
    # Thread count
    THREAD_COUNT=$(ls /proc/"$PID"/task 2>/dev/null | wc -l || echo "?")

    # RCON data (TPS, regions, players)
    TPS="?"
    MSPT="?"
    REGIONS="?"
    PLAYERS="?"
    
    if [ -n "$RCON_CMD" ]; then
        # Try to get TPS info
        TPS_OUTPUT=$(rcon_exec "tps" 2>/dev/null || true)
        if echo "$TPS_OUTPUT" | grep -qiE '[0-9]+\.[0-9]+'; then
            TPS=$(echo "$TPS_OUTPUT" | grep -oP '[0-9]+\.[0-9]+' | head -1 || echo "?")
        fi
        
        PLAYER_OUTPUT=$(rcon_exec "list" 2>/dev/null || true)
        if echo "$PLAYER_OUTPUT" | grep -qiE '[0-9]+'; then
            PLAYERS=$(echo "$PLAYER_OUTPUT" | grep -oP '[0-9]+' | head -1 || echo "?")
        fi
    fi

    # Log to CSV
    echo "${TIMESTAMP},${CPU_PERCENT},${MEM_MB},${MEM_TOTAL},${TPS},${MSPT},${REGIONS},${PLAYERS},${THREAD_COUNT}" >> "$LOG_FILE"

    # Display
    clear
    echo -e "${CYAN}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}║${BOLD}  EnigmaEngine Performance Monitor  ${NC}${CYAN}                         ║${NC}"
    echo -e "${CYAN}╠══════════════════════════════════════════════════════════════╣${NC}"
    echo -e "${CYAN}║${NC}  ${BOLD}Time:${NC}      $TIMESTAMP                          ${CYAN}║${NC}"
    echo -e "${CYAN}║${NC}  ${BOLD}PID:${NC}       $PID                                            ${CYAN}║${NC}"
    echo -e "${CYAN}╠══════════════════════════════════════════════════════════════╣${NC}"
    
    # TPS Bar
    if [[ "$TPS" =~ ^[0-9]+\.?[0-9]*$ ]]; then
        TPS_INT=$(printf "%.0f" "$TPS")
        if [ "$TPS_INT" -ge 19 ]; then
            TPS_COLOR=$GREEN
        elif [ "$TPS_INT" -ge 15 ]; then
            TPS_COLOR=$YELLOW
        else
            TPS_COLOR=$RED
        fi
        TPS_BAR_LEN=$((TPS_INT * 2))
        TPS_BAR=$(printf '█%.0s' $(seq 1 $TPS_BAR_LEN 2>/dev/null) 2>/dev/null || echo "████████████████████")
        echo -e "${CYAN}║${NC}  ${BOLD}TPS:${NC}       ${TPS_COLOR}${TPS} ${TPS_BAR}${NC}"
    else
        echo -e "${CYAN}║${NC}  ${BOLD}TPS:${NC}       ${YELLOW}Waiting for data...${NC}"
    fi

    # CPU Bar
    CPU_INT=$(printf "%.0f" "$CPU_PERCENT" 2>/dev/null || echo "0")
    CPU_PER_CORE=$((CPU_INT / 7))
    if [ "$CPU_PER_CORE" -le 60 ]; then
        CPU_COLOR=$GREEN
    elif [ "$CPU_PER_CORE" -le 85 ]; then
        CPU_COLOR=$YELLOW
    else
        CPU_COLOR=$RED
    fi
    echo -e "${CYAN}║${NC}  ${BOLD}CPU:${NC}       ${CPU_COLOR}${CPU_PERCENT}%${NC} total (${CPU_PER_CORE}%/core avg)"
    echo -e "${CYAN}║${NC}  ${BOLD}Cores:${NC}     ${CORE_USAGE}"
    
    # Memory Bar
    MEM_PERCENT=$((MEM_MB * 100 / 16384))
    if [ "$MEM_PERCENT" -le 70 ]; then
        MEM_COLOR=$GREEN
    elif [ "$MEM_PERCENT" -le 85 ]; then
        MEM_COLOR=$YELLOW
    else
        MEM_COLOR=$RED
    fi
    echo -e "${CYAN}║${NC}  ${BOLD}Memory:${NC}    ${MEM_COLOR}${MEM_MB}M / 16384M (${MEM_PERCENT}%)${NC}"
    echo -e "${CYAN}║${NC}  ${BOLD}Threads:${NC}   ${THREAD_COUNT}"
    echo -e "${CYAN}║${NC}  ${BOLD}Players:${NC}   ${PLAYERS}"
    echo -e "${CYAN}╠══════════════════════════════════════════════════════════════╣${NC}"
    echo -e "${CYAN}║${NC}  ${BOLD}Log:${NC}       logs/monitor.csv"
    echo -e "${CYAN}║${NC}  Press ${BOLD}Ctrl+C${NC} to stop monitoring"
    echo -e "${CYAN}╚══════════════════════════════════════════════════════════════╝${NC}"

    sleep "$INTERVAL"
done
