#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRAMEWORK_RES="${ANDROID_FRAMEWORK_RES:-/system/framework/framework-res.apk}"
ANDROID_JAR="${ANDROID_JAR:-/data/data/com.termux/files/usr/share/java/android-24.jar}"
KEYSTORE="${CREW_HELPER_KEYSTORE:-$SCRIPT_DIR/test.keystore}"
OUT_APK="$SCRIPT_DIR/CrewHelper.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "Creating development test.keystore..."
    keytool -genkey -v -keystore "$KEYSTORE" -alias crewhelper -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=CrewHelper, OU=OpenSource, O=CrewPocket, L=Taipei, ST=Taiwan, C=TW" \
        -storepass password -keypass password
fi

cd "$SCRIPT_DIR"
rm -rf bin
mkdir -p bin/classes bin/gen

echo "1. Generating R.java..."
aapt package -f -m -J bin/gen -S app/src/main/res -M app/src/main/AndroidManifest.xml -I "$FRAMEWORK_RES"

echo "2. Compiling Java classes..."
javac -d bin/classes -cp "$ANDROID_JAR:$SCRIPT_DIR/app/libs/*" bin/gen/com/crewpocket/helper/R.java app/src/main/java/com/crewpocket/helper/*.java

echo "3. Converting to DEX..."
d8 --output bin/ bin/classes/com/crewpocket/helper/*.class app/libs/*.jar

echo "4. Packaging APK..."
aapt package -f -M app/src/main/AndroidManifest.xml -S app/src/main/res -I "$FRAMEWORK_RES" -F bin/unsigned.apk

cd bin
aapt add unsigned.apk classes.dex
cd "$SCRIPT_DIR"

echo "5. Signing APK..."
apksigner sign --ks "$KEYSTORE" \
  --ks-pass "pass:${CREW_HELPER_KEYSTORE_PASS:-123456}" \
  --key-pass "pass:${CREW_HELPER_KEY_PASS:-123456}" \
  --out "$OUT_APK" \
  bin/unsigned.apk

echo "✅ SUCCESS: Built $OUT_APK"
