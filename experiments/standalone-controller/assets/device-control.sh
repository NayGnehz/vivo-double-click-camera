#!/system/bin/sh
# Installed and invoked as the ADB shell user. Never takes exclusive input grabs.
set -u

DIR=/data/local/tmp/vivo-double-click
JAR="$DIR/vivo-camera.jar"
PIDFILE="$DIR/service.pid"
STOPFILE="$DIR/stop.request"
LOG="$DIR/service.log"
MAIN=local.vivodoubleclick.Main
BOOTIDFILE=/proc/sys/kernel/random/boot_id

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

valid_boot_uuid() {
    printf '%s\n' "$1" | grep -E -q '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
}

read_pid() {
    pid=
    boot_uuid=
    [ -f "$PIDFILE" ] || return 1
    # Read both lines through one open file: Main replaces this file atomically.
    {
        IFS= read -r pid || :
        IFS= read -r boot_uuid || :
    } 2>/dev/null < "$PIDFILE" || return 1
    case "$pid" in ''|*[!0-9]*|0|1) return 1 ;; esac
    [ "${#pid}" -le 10 ] || return 1
    # Legacy one-line files and invalid/missing boot markers retain PID protection.
    valid_boot_uuid "$boot_uuid" || boot_uuid=
    return 0
}

is_main_pid() {
    kill -0 "$pid" 2>/dev/null || return 1
    [ -r "/proc/$pid/cmdline" ] || return 1
    command -v timeout >/dev/null 2>&1 || fail "timeout is unavailable; cannot safely verify process identity"
    # Bound the reader itself, including opening /proc, so grep receives EOF on timeout.
    timeout 2 sh -c 'exec tr "\000" "\n" < "$1"' sh "/proc/$pid/cmdline" 2>/dev/null \
        | grep -F -x "$MAIN" >/dev/null 2>&1
}

remove_own_pid_file() {
    # Do not remove a newer process's PID file.
    remove_expected_pid="$pid"
    remove_expected_boot_uuid="$boot_uuid"
    removed_pid_file=0
    if read_pid && [ "$pid" = "$remove_expected_pid" ] && [ "$boot_uuid" = "$remove_expected_boot_uuid" ]; then
        rm -f "$PIDFILE" || fail "Could not remove stale PID file"
        removed_pid_file=1
    fi
    # read_pid mutates globals, so restore the caller's complete process identity.
    pid="$remove_expected_pid"
    boot_uuid="$remove_expected_boot_uuid"
}

discard_previous_boot_state() {
    read_pid || return 0
    [ -n "$boot_uuid" ] || return 0
    current_boot_uuid=
    { IFS= read -r current_boot_uuid; } 2>/dev/null < "$BOOTIDFILE" || return 0
    valid_boot_uuid "$current_boot_uuid" || return 0
    [ "$boot_uuid" != "$current_boot_uuid" ] || return 0
    remove_own_pid_file
    if [ "$removed_pid_file" -eq 1 ]; then
        rm -f "$STOPFILE" || fail "Could not clear previous boot's stop request"
        echo "STALE: cleared PID $pid from a previous boot; no process was signalled"
    fi
}

stop_service() {
    discard_previous_boot_state
    if [ ! -f "$PIDFILE" ]; then
        rm -f "$STOPFILE"
        echo "STOPPED: no PID file"
        return 0
    fi
    read_pid || fail "Invalid PID file: $PIDFILE; no process was killed"
    if ! is_main_pid; then
        if [ -d "/proc/$pid" ]; then
            fail "PID $pid does not identify $MAIN; no process was killed"
        fi
        remove_own_pid_file
        rm -f "$STOPFILE"
        echo "STOPPED: removed stale PID file"
        return 0
    fi
    # ART does not reliably run shutdown hooks for SIGTERM. Ask Main to clean up
    # its own getevent children and exit normally through its FileObserver.
    rm -f "$STOPFILE" || fail "Could not clear stale stop request"
    touch "$STOPFILE" || fail "Could not request a clean stop"
    remaining=50
    while kill -0 "$pid" 2>/dev/null && [ "$remaining" -gt 0 ]; do
        sleep 0.1
        remaining=$((remaining - 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
        fail "PID $pid did not stop within 5 seconds; no termination signal was sent"
    fi
    remove_own_pid_file
    rm -f "$STOPFILE"
    echo "STOPPED: PID $pid"
}

start_service() {
    discard_previous_boot_state
    double_ms=350
    delay_ms=150
    dry_run=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --double-ms)
                [ "$#" -ge 2 ] || fail "Missing --double-ms value"
                double_ms="$2"; shift 2 ;;
            --delay-ms)
                [ "$#" -ge 2 ] || fail "Missing --delay-ms value"
                delay_ms="$2"; shift 2 ;;
            --dry-run) dry_run=1; shift ;;
            *) fail "Unknown start argument: $1" ;;
        esac
    done
    case "$double_ms" in ''|*[!0-9]*) fail "Invalid double-click interval" ;; esac
    case "$delay_ms" in ''|*[!0-9]*) fail "Invalid launch delay" ;; esac
    [ "${#double_ms}" -le 3 ] && [ "$double_ms" -ge 150 ] && [ "$double_ms" -le 600 ] \
        || fail "--double-ms must be between 150 and 600"
    [ "${#delay_ms}" -le 4 ] && [ "$delay_ms" -ge 0 ] && [ "$delay_ms" -le 1000 ] \
        || fail "--delay-ms must be between 0 and 1000"
    [ -s "$JAR" ] || fail "Missing or empty JAR: $JAR"
    command -v nohup >/dev/null 2>&1 || fail "nohup is unavailable"
    command -v setsid >/dev/null 2>&1 || fail "setsid is unavailable"
    command -v app_process >/dev/null 2>&1 || fail "app_process is unavailable"
    if [ -f "$PIDFILE" ]; then
        read_pid || fail "Invalid PID file: $PIDFILE"
        if is_main_pid; then
            fail "Already running as PID $pid; stop it before starting"
        fi
        [ ! -d "/proc/$pid" ] || fail "PID $pid belongs to another process; refusing start"
        remove_own_pid_file
    fi
    set -- --double-ms "$double_ms" --delay-ms "$delay_ms"
    [ "$dry_run" -eq 0 ] || set -- "$@" --dry-run
    rm -f "$STOPFILE" || fail "Could not clear stale stop request"
    # No terminal, inherited ADB streams, sockets, or shell job supervision remain.
    CLASSPATH="$JAR" nohup setsid app_process "$DIR" "$MAIN" "$@" \
        </dev/null >"$LOG" 2>&1 &
    remaining=100
    while [ "$remaining" -gt 0 ]; do
        if read_pid && is_main_pid && grep -E -q '^READY([ :]|$)' "$LOG"; then
            echo "READY: PID $pid; log $LOG"
            tail -n 12 "$LOG"
            return 0
        fi
        sleep 0.1
        remaining=$((remaining - 1))
    done
    tail -n 30 "$LOG" >&2
    if read_pid && is_main_pid; then
        stop_service >&2
    fi
    fail "Service did not become READY within 10 seconds"
}

action="${1:-}"
[ "$#" -eq 0 ] || shift
case "$action" in
    start) start_service "$@" ;;
    stop) [ "$#" -eq 0 ] || fail "stop takes no arguments"; stop_service ;;
    status)
        [ "$#" -eq 0 ] || fail "status takes no arguments"
        discard_previous_boot_state
        if read_pid && is_main_pid; then
            echo "RUNNING: PID $pid; log $LOG"
        else
            echo "STOPPED"
            exit 1
        fi ;;
    *) fail "Usage: sh device-control.sh start [--double-ms 350] [--delay-ms 150] [--dry-run] | stop | status" ;;
esac
