#!/usr/bin/env bash
# Crew Helper: local ADB diagnostics; never reads screenshots/transcripts/secrets.
set -euo pipefail

PACKAGE="com.crewpocket.helper"
HELPER="crew-bg-helper"
LOG="/data/local/tmp/crew-bg-helper.log"

adb_run() {
  if [[ -n "$(printenv ANDROID_SERIAL 2>/dev/null || true)" ]]; then
    command adb -s "$(printenv ANDROID_SERIAL)" "$@"
  else
    command adb "$@"
  fi
}

require_device() {
  command -v adb >/dev/null || { echo "adb not installed" >&2; exit 1; }
  local serial
  serial="$(adb_run get-serialno 2>/dev/null | tr -d '\r')"
  [[ -n "$serial" && "$serial" != "unknown" ]] || {
    echo "No authorized ADB device; check adb devices" >&2
    exit 1
  }
}

read_pid() {
  adb_run shell pidof "$HELPER" 2>/dev/null | tr -d '\r' || true
}

check() {
  local api installed pids
  api="$(adb_run shell getprop ro.build.version.sdk | tr -d '\r')"
  installed="$(adb_run shell pm path "$PACKAGE" | tr -d '\r' | head -n 1)"
  pids="$(read_pid)"
  echo "Android API: $api"
  echo "Crew Helper APK: $installed"
  echo "Shell helper PID: $pids"
  echo "Manual control: Crew Helper > Settings > Advanced > Background Display POC"
  [[ -n "$installed" ]] || { echo "Crew Helper not installed" >&2; exit 2; }
  [[ "$api" =~ ^[0-9]+$ && "$api" -ge 31 ]] || {
    echo "Requires Android 12+ (API 31)" >&2
    exit 3
  }
}

start_helper() {
  local pids
  pids="$(read_pid)"
  if [[ -n "$pids" ]]; then
    echo "Helper running at PID $pids; use 'restart' after updating APK."
    return 0
  fi
  adb_run shell '
    APK="$(pm path com.crewpocket.helper | head -n 1 | cut -d: -f2)"
    [ -n "$APK" ] && [ -f "$APK" ] || exit 3
    CLASSPATH="$APK" nohup setsid app_process /system/bin \
      --nice-name=crew-bg-helper \
      com.crewpocket.helper.BackgroundDisplayShell \
      >/data/local/tmp/crew-bg-helper.log 2>&1 </dev/null &
  '
  echo "Helper launch requested; check in the App UI and use logs if necessary."
}

restart_helper() {
  local pids pid
  pids="$(read_pid)"
  for pid in $pids; do
    [[ "$pid" =~ ^[0-9]+$ ]] || { echo "Unexpected PID; refusing kill" >&2; exit 4; }
    adb_run shell kill "$pid"
    echo "Stopped helper PID $pid"
  done
  start_helper
}

usage() {
  echo "Usage: bash scripts/background_display_local.sh check|start|restart|logs"
  echo "Set ANDROID_SERIAL if more than one ADB device is connected."
}

main() {
  [[ $# -eq 1 ]] || { usage; exit 2; }
  case "$1" in
    check) require_device; check ;;
    start) require_device; check; start_helper ;;
    restart) require_device; check; restart_helper ;;
    logs) require_device; adb_run shell tail -n 40 "$LOG" ;;
    *) usage; exit 2 ;;
  esac
}

main "$@"
