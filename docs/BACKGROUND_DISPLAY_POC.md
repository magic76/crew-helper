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

## Stage 2: Gemini Live semantic virtual-display experiment

Opt-in at **Crew Helper > Settings > Advanced > Background Display POC >
啟用 Gemini Live 背景操作**. Default is OFF; restart Live to apply the tool
catalog. Start ADB Shell helper first. On a new user turn, explicitly say
"背景" / "虛擬螢幕" so the tool cannot silently redirect front-screen actions.

Voice test:「在背景螢幕打開 Apple Music，看看畫面。」
The model uses background_phone(OPEN_APP) then INSPECT. INSPECT reads only
windows on the created virtual display, with existing SemanticScreenState
and SensitiveDataGuard redaction. TAP/SCROLL/TYPE require a one-shot token,
an exact accessible element ID, and the same screen fingerprint. There is
no model-controlled coordinate fallback or accidental display-0 redirect.
Ask「把背景的 App 交給我看」for HANDOFF, or「關閉背景螢幕」for CLOSE.

This remains experimental: dispatch and a changed fingerprint do NOT
prove the user's full goal. It requires Galaxy S25 Ultra real-device
testing and is not yet the production AgentRuntimeV2 verifier. Use only
non-sensitive Apps/screens; no payment, messaging commits or credentials.

### Updating from the Stage 1 test build

After installing v1.8.115, **stop the old background Shell Helper** so the
abstract socket is not still served by the previous APK process:

    adb shell pidof crew-bg-helper
    adb shell kill <EXACT_PID_FROM_ABOVE>

Then re-run the documented `adb shell 'APK=... app_process ...'` helper
command. Confirm the new helper is responding before enabling Live mode.
Avoid broad `pkill -f` matching; it can kill unrelated processes.
