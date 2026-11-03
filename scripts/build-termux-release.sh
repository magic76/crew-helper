#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.cache/android-sdk}}"
GRADLE_BIN="${CREW_GRADLE_BIN:-$HOME/.cache/gradle-9.6.0/bin/gradle}"
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
JAVA_17_HOME="${CREW_JAVA_17_HOME:-$TERMUX_PREFIX/lib/jvm/java-17-openjdk}"
KEYSTORE="${CREW_RELEASE_KEYSTORE:-$HOME/.local/share/crew-helper/keys/CrewHelper-release.keystore}"
KEY_ALIAS="${CREW_RELEASE_ALIAS:-crew}"
DOWNLOADS="${CREW_DOWNLOADS_DIR:-$HOME/storage/downloads}"
EXPECTED_CERT_SHA256="fdf3c9351b55a2dfb403d7892ec933acdf5850aa18f22d18bf85e9155cec3db7"
INSTALL_APK=1

usage() {
    cat <<'EOF'
Usage: scripts/build-termux-release.sh [--no-install]

Builds and signs the Android release APK, verifies its signature and release
certificate, copies it to the versioned Downloads path, then installs it over
wireless ADB by default. --no-install skips only the final install step.
EOF
}

for argument in "$@"; do
    case "$argument" in
        --no-install) INSTALL_APK=0 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown argument: $argument" >&2; usage >&2; exit 2 ;;
    esac
done

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

[[ -x "$GRADLE_BIN" ]] || fail "Gradle not found at $GRADLE_BIN (set CREW_GRADLE_BIN)."
[[ -d "$SDK_ROOT/platforms" ]] || fail "Android SDK not found at $SDK_ROOT."
[[ -d "$SDK_ROOT/build-tools" ]] || fail "Android SDK build-tools not found under $SDK_ROOT."
[[ -x "$JAVA_17_HOME/bin/java" && -x "$JAVA_17_HOME/bin/javac" ]] \
    || fail "Java 17 not found at $JAVA_17_HOME (set CREW_JAVA_17_HOME)."
[[ -f "$KEYSTORE" ]] || fail "Release keystore not found at $KEYSTORE (set CREW_RELEASE_KEYSTORE)."
command -v aidl >/dev/null 2>&1 || fail "Termux aidl is missing; install/configure Android build tools first."
command -v aapt >/dev/null 2>&1 || fail "Termux aapt is missing; install/configure Android build tools first."
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required."

BUILD_TOOLS_VERSION="$(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' \
    | sort -V | tail -n 1)"
[[ -n "$BUILD_TOOLS_VERSION" ]] || fail "No Android SDK build-tools installed under $SDK_ROOT."
BUILD_TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
SDK_AIDL="$BUILD_TOOLS/aidl"
APKSIGNER="$BUILD_TOOLS/apksigner"
[[ -f "$SDK_AIDL" ]] || fail "SDK AIDL compiler not found: $SDK_AIDL."
[[ -f "$APKSIGNER" ]] || fail "apksigner not found: $APKSIGNER."

# Android SDK's AIDL binary is often x86_64 and cannot execute on Termux ARM64.
# Temporarily route Gradle's AIDL calls through Termux's native AIDL compiler.
AIDL_BACKUP=""
restore_aidl() {
    if [[ -n "$AIDL_BACKUP" && -e "$AIDL_BACKUP" ]]; then
        rm -f "$SDK_AIDL"
        mv "$AIDL_BACKUP" "$SDK_AIDL"
    fi
}
if ! "$SDK_AIDL" --help >/dev/null 2>&1; then
    AIDL_BACKUP="$SDK_AIDL.crew-backup.$$"
    mv "$SDK_AIDL" "$AIDL_BACKUP"
    trap restore_aidl EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    cat > "$SDK_AIDL" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
exec "$(command -v aidl)" --lang=java "\$@"
EOF
    chmod 755 "$SDK_AIDL"
fi

cd "$ROOT"
echo "Building release APK with $GRADLE_BIN..."
env -u LD_LIBRARY_PATH JAVA_HOME="$JAVA_17_HOME" PATH="$JAVA_17_HOME/bin:$PATH" \
    ANDROID_HOME="$SDK_ROOT" ANDROID_SDK_ROOT="$SDK_ROOT" \
    "$GRADLE_BIN" --no-daemon :app:assembleRelease
restore_aidl
trap - EXIT INT TERM

UNSIGNED_APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$UNSIGNED_APK" ]] || fail "Gradle did not produce $UNSIGNED_APK."

if [[ -z "${CREW_RELEASE_STORE_PASSWORD:-}" ]]; then
    read -r -s -p "Release keystore password: " CREW_RELEASE_STORE_PASSWORD
    echo
fi
if [[ -z "${CREW_RELEASE_KEY_PASSWORD:-}" ]]; then
    read -r -s -p "Release key password: " CREW_RELEASE_KEY_PASSWORD
    echo
fi
export CREW_RELEASE_STORE_PASSWORD CREW_RELEASE_KEY_PASSWORD

SIGNED_APK="$ROOT/app/build/outputs/apk/release/CrewHelper-release-signed.apk"
echo "Signing APK with alias '$KEY_ALIAS'..."
bash "$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:CREW_RELEASE_STORE_PASSWORD \
    --key-pass env:CREW_RELEASE_KEY_PASSWORD \
    --out "$SIGNED_APK" \
    "$UNSIGNED_APK"

VERIFY_OUTPUT="$(bash "$APKSIGNER" verify --verbose --print-certs "$SIGNED_APK")"
printf '%s\n' "$VERIFY_OUTPUT"
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$VERIFY_OUTPUT" \
    || fail "APK v2 signature verification failed."
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' <<<"$VERIFY_OUTPUT" \
    || fail "APK v3 signature verification failed."
CERT_SHA256="$(sed -n 's/.*certificate SHA-256 digest: //p' <<<"$VERIFY_OUTPUT" | head -n 1 | tr '[:upper:]' '[:lower:]')"
[[ "$CERT_SHA256" == "$EXPECTED_CERT_SHA256" ]] \
    || fail "Release certificate mismatch (got $CERT_SHA256). Do not install this APK as an update."

BADGING="$(aapt dump badging "$SIGNED_APK")"
PACKAGE_LINE="$(sed -n '/^package: /{p;q;}' <<<"$BADGING")"
PACKAGE_NAME="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$PACKAGE_LINE")"
VERSION_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$PACKAGE_LINE")"
VERSION_CODE="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$PACKAGE_LINE")"
[[ "$PACKAGE_NAME" == "com.crewpocket.helper" ]] || fail "Unexpected package name: $PACKAGE_NAME."
[[ -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] || fail "Could not read APK version metadata."

mkdir -p "$DOWNLOADS"
DEST_APK="$DOWNLOADS/CrewHelper-v$VERSION_NAME.apk"
cp -f "$SIGNED_APK" "$DEST_APK"
SOURCE_SHA256="$(sha256sum "$SIGNED_APK" | awk '{print $1}')"
DEST_SHA256="$(sha256sum "$DEST_APK" | awk '{print $1}')"
[[ "$SOURCE_SHA256" == "$DEST_SHA256" ]] || fail "Downloads copy checksum mismatch."
echo "Release: $PACKAGE_NAME $VERSION_NAME (versionCode $VERSION_CODE)"
echo "Certificate SHA-256: $CERT_SHA256"
echo "APK SHA-256: $DEST_SHA256"
echo "Copied: $DEST_APK"

if (( INSTALL_APK )); then
    INSTALL_SCRIPT="${CREW_INSTALL_APK_SCRIPT:-$HOME/install-apk.sh}"
    [[ -f "$INSTALL_SCRIPT" ]] || fail "Install helper not found: $INSTALL_SCRIPT."
    bash "$INSTALL_SCRIPT" "$DEST_APK"
    echo "Installed over wireless ADB: $DEST_APK"
else
    echo "Install skipped (--no-install)."
fi
