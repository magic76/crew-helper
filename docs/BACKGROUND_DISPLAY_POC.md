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

## Stage 3 — reliability and local diagnostics (v1.8.116)

- Shell protocol version 3 is checked *before* each state-mutating command.
  A stale helper returns `HELPER_RESTART_REQUIRED` and refuses execution.
- `OPEN_APP` succeeds only when Android reports the target task on the
  allocated virtual display, not merely when `am start` exits 0.
- `HANDOFF` succeeds only when Android reports the *same task ID* on
  display 0. Otherwise it returns `HANDOFF_NOT_VERIFIED` and retains
  the virtual display instead of falsely declaring success.
- A Live session will **not adopt a background App launched by the manual
  experiment page or a previous Live session**. Close the manual display
  first, then ask Live to open the App in background.
- Background screen tokens are single-use even when mutation is rejected.
  Dispatch is `STEP_PENDING` until a separate INSPECT confirms evidence.
- Natural-language negative commands such as 「不要切到主螢幕」and
  「不要關閉背景」do not authorize handoff or release.
- Foreground `phone_action` is rejected when the current finalized user
  instruction explicitly requests a virtual/background screen.
- The experiment page has a **Copy diagnostic** button. It copies only
  operation, result code, protocol, virtual display ID and focus-isolation
  flag: no screenshots, transcripts, user content or API keys.

### Recommended S25 Ultra acceptance checks

1. With helper OFF, `Check Helper` must report `HELPER_NOT_CONNECTED`.
2. After installing v1.8.116 **without restarting helper**, check must report
   `HELPER_RESTART_REQUIRED`. Restart the helper, then check protocol 3.
3. Open Apple Music on virtual screen; inspect/tap without affecting the
   physical display. Verify focusIsolated is true (if false, record One UI
   shared-focus limitation).
4. Move the task to the main display. Confirm same task's UI is visible and
   navigation state remains. Repeat on Google Maps with a non-navigation UI.
5. In Live, explicitly say 「在背景螢幕打開 Apple Music」then INSPECT and TAP.
   Verify a blocked or stale screen token never becomes a physical tap.
6. Say 「不要切到主螢幕」and 「不要關閉背景」; neither may move/close it.
7. Disable experiment; confirm ordinary Live map/music interaction still
   succeeds and virtual display tool returns disabled.
8. Copy the non-sensitive diagnostic summary and note behavior/failure code.

These are acceptance tests to perform locally; GitHub CI cannot test One UI,
virtual display privilege, task moving or third-party App compatibility.

- Live handoff/close now includes expected display ID and package. The
  Shell helper refuses the request if its actual display changed between
  inspection and command, instead of moving/closing an unrelated session.
