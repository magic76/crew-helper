# Background Display POC (Crew Helper)

This is an isolated manual experiment based on ideas from Mika v0.1.33
(https://github.com/wangzan101/Mika/tree/v0.1.33, Apache-2.0).

## Scope

- Shell-owned virtual display (requires adb shell UID 2000)
- App launch onto background display, JPEG preview and coordinate tapping
- Move the same task to the physical display before releasing the virtual display
- Reject launch when target App is visible on the physical screen
- Android abstract LocalSocket with kernel-provided peer UID check
- **No Gemini Live tools, no automatic AI actions, no change to existing behavior**

## Test on Android 12+ (Android 14+ recommended)

1. Build/install Crew Helper. Enable authorized USB or wireless ADB.
2. Start the helper in a computer shell (do not execute as a normal Android app):

    adb shell 'APK=$(pm path com.crewpocket.helper | head -n 1 | cut -d: -f2); CLASSPATH="$APK" nohup setsid app_process /system/bin --nice-name=crew-bg-helper com.crewpocket.helper.BackgroundDisplayShell >/data/local/tmp/crew-bg-helper.log 2>&1 </dev/null &'

3. Open Crew Helper > Settings > Advanced > Background Display POC.
4. Check helper, start a package, update preview, and tap inside preview.
5. Use Move to foreground to hand the running task to the user, or Close.
6. For failures: adb shell cat /data/local/tmp/crew-bg-helper.log

To terminate helper, get its exact PID with adb shell pidof crew-bg-helper,
then run adb shell kill PID (substitute the returned numeric PID).

## Known limits

- Manual ADB launch only. Wireless pairing/auto-restart are NOT implemented.
- Some OEM versions deny trusted virtual displays, causing focus interference.
- Some apps cannot launch or render correctly on a secondary display.
- Handoff moves an Android task, not pixels; an Activity may be recreated.
- Preview is visible only in the diagnostic Activity and never sent to a model.
- The POC has no sensitive-window classification or accessibility node semantics.
  Do not use it for logins, OTPs, payment, or private account workflows.
- Do not connect it to autonomous Gemini Live operation until semantic targeting,
  session ownership, permission UX, action verification and cancellation are built.

## Next gate

On a real Galaxy S25 Ultra, compare foreground vs. virtual behavior for
Google Maps and Apple Music, especially focus, task continuity and failures.
The production Live/Accessibility path stays unchanged until real-device evidence.
