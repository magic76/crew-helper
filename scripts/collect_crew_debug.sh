#!/usr/bin/env bash
# Pull an already-exported Crew Debug Lab ZIP from a *debuggable* Android app.
# Never reads raw Logcat, device files outside the app cache, or user audio.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-"$ROOT/debug-output"}"
APP="com.crewpocket.helper"
mkdir -p "$OUT"
TMP="$OUT/.crew-debug-lab.tmp.zip"
ZIP="$OUT/crew-debug-lab.zip"
REPORT="$OUT/crew-debug-analysis.md"
trap 'rm -f "$TMP"' EXIT

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install Android Platform Tools, or share ZIP from Inspector." >&2
  exit 2
fi

if ! adb exec-out run-as "$APP" cat cache/crew_debug_lab/latest.zip > "$TMP"; then
  echo "Unable to read Debug Lab export. Use a debuggable APK, enable Debug Lab," >&2
  echo "reproduce the issue, then tap Export Debug ZIP in Agent Inspector." >&2
  echo "For a release APK, use Android's manual share option." >&2
  exit 2
fi

python3 "$ROOT/scripts/diagnose_debug_bundle.py" "$TMP" --out "$REPORT"
mv "$TMP" "$ZIP"
echo "ZIP: $ZIP"
echo "Analysis: $REPORT"
