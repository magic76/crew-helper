# Termux Android release APK

Use the Gradle release workflow for an APK that can update the installed Crew Helper. The older top-level `build.sh` is a lightweight development build and uses a local test key; it is not the release signing path.

## One-command release

From the repository root:

```sh
./scripts/build-termux-release.sh
```

The script builds `:app:assembleRelease`, signs the unsigned APK with the local release keystore, verifies APK Signature Scheme v2 and v3 plus the known release certificate, reads the package version, and copies the result to:

```text
~/storage/downloads/CrewHelper-v<versionName>.apk
```

It then calls `~/install-apk.sh` to install through wireless ADB. Use `--no-install` only when an install is not wanted:

```sh
./scripts/build-termux-release.sh --no-install
```

The script prompts for the keystore and key passwords without echoing them. It never writes credentials into Gradle properties or the repository.

## Local requirements and overrides

Defaults match the Termux build environment:

- SDK: `~/.cache/android-sdk`
- Gradle: `~/.cache/gradle-9.6.0/bin/gradle`
- Java 17: `$PREFIX/lib/jvm/java-17-openjdk`
- Release key: `~/.local/share/crew-helper/keys/CrewHelper-release.keystore`, alias `crew`
- Downloads: `~/storage/downloads`
- Java toolchain: 17, matching the Android CI workflow

Override paths when needed:

| Variable | Purpose |
| --- | --- |
| `ANDROID_SDK_ROOT` or `ANDROID_HOME` | Android SDK root |
| `CREW_GRADLE_BIN` | Gradle 9.6.0 executable |
| `CREW_JAVA_17_HOME` | Java 17 home directory |
| `CREW_RELEASE_KEYSTORE` | Release keystore path |
| `CREW_RELEASE_ALIAS` | Key alias |
| `CREW_RELEASE_STORE_PASSWORD` | Keystore password, if supplied non-interactively |
| `CREW_RELEASE_KEY_PASSWORD` | Key password, if supplied non-interactively |
| `CREW_DOWNLOADS_DIR` | Destination directory |
| `CREW_INSTALL_APK_SCRIPT` | Wireless ADB install helper |

Never commit `.keystore`, `.jks`, key passwords, or `key.properties`. The release certificate fingerprint in the script is public metadata used to prevent accidentally signing with a different key.

## Termux AIDL note

On ARM64 Termux, the AIDL executable shipped inside Android SDK build-tools may be an x86-64 host binary. The script temporarily swaps only that SDK executable for a wrapper to Termux's native `aidl`, and restores the original file on exit, including build failures. Do not copy the wrapper into the repo or leave it installed in the SDK.

The Gradle release task does not run the agent runtime replay tests. CI runs those separately before its release build.

## If install fails

The signed APK remains in Downloads. Confirm Android Wireless debugging is enabled and the current ADB port is configured, then run `~/set-adb.sh <port>` and rerun the install. The build script does not silently fall back to USB or install against an unverified device.

For an update-install rejection, check that the package is `com.crewpocket.helper` and that the APK's certificate check passed. Do not replace the user's installed app with a differently signed debug/test APK.
