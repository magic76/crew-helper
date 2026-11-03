#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Ensure the removed privileged transport cannot re-enter the shipped APK.
removed=(
  app/src/main/aidl/com/crewpocket/helper/IBackgroundDirectShell.aidl
  app/src/main/aidl/com/crewpocket/helper/IBackgroundShizukuService.aidl
  app/src/main/java/com/crewpocket/helper/BackgroundShizukuService.java
  app/src/main/java/com/crewpocket/helper/BackgroundShellHandoffProvider.java
  app/src/main/java/com/crewpocket/helper/BackgroundDisplayShell.java
  app/src/main/java/com/crewpocket/helper/BackgroundDisplayExperimentActivity.java
  app/src/main/java/com/crewpocket/helper/BackgroundPhoneRuntime.java
  scripts/background_display_local.sh
)
for file in "${removed[@]}"; do
  if [[ -e "$ROOT/$file" ]]; then
    echo "Retired privileged artifact unexpectedly present: $file" >&2
    exit 1
  fi
done

manifest="$ROOT/app/src/main/AndroidManifest.xml"
gradle="$ROOT/app/build.gradle"
catalog="$ROOT/app/src/main/java/com/crewpocket/helper/LiveToolCatalog.java"
main="$ROOT/app/src/main/java/com/crewpocket/helper/MainActivity.java"
runtime="$ROOT/app/src/main/java/com/crewpocket/helper/NativeGeminiLiveClient.java"

if grep -Eiq 'shizuku|BackgroundShellHandoffProvider|BackgroundDisplayExperimentActivity' "$manifest"; then
  echo "Retired privileged declaration in manifest" >&2
  exit 1
fi
if grep -Eiq 'shizuku' "$gradle"; then
  echo "Shizuku dependency is still present" >&2
  exit 1
fi
if grep -Eq 'background_phone|BackgroundPhoneRuntime' "$catalog"; then
  echo "Retired background tool exposed to model" >&2
  exit 1
fi
if grep -Eq 'BackgroundDisplayExperimentActivity' "$main"; then
  echo "Retired Settings entry visible" >&2
  exit 1
fi
if ! grep -q 'RetiredVirtualDisplayPolicy.mustBlock' "$runtime" ||
   ! grep -q 'VIRTUAL_DISPLAY_REMOVED' "$runtime"; then
  echo "Fail-closed guard is missing from Live Runtime" >&2
  exit 1
fi
if grep -Eq 'backgroundPhoneRuntime|executeBackgroundLiveTool|sendBackgroundJpegBytes' "$runtime"; then
  echo "Retired virtual-display execution code remains" >&2
  exit 1
fi

echo "Privileged Shizuku / ADB virtual-display removal verified"
