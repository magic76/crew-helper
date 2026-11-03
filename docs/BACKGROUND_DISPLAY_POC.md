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

### One-command local ADB utility

From a Mac/Linux/Termux terminal with authorized ADB:

```sh
bash scripts/background_display_local.sh check
bash scripts/background_display_local.sh start
# When updating the APK or if the Helper reports old protocol:
bash scripts/background_display_local.sh restart
bash scripts/background_display_local.sh logs
```

`restart` explicitly kills **only the exact** `crew-bg-helper` PID, never
a broad matching process name. Do not run `restart` during active
background work. `logs` may contain device diagnostic details: review
before sharing.


## Stage 4 — bounded background execution (v1.8.117)

- An opened virtual App gets a fresh per-session budget of **10 mutation
  attempts and 18 inspections**. Shell STATUS, explicit HANDOFF, and CLOSE
  remain possible after the budget is reached; there is no infinite AI tap
  loop. To reset, finish/close the existing session and start a fresh App.
- Every `INSPECT` issues a screen token scoped to the **authoritative finalized
  user generation**. A new user turn must INSPECT again, even if 15 seconds
  have not yet elapsed. A mutation attempt consumes that token.
- If a mutation is dispatched but an actual screen change is not observed,
  the exact same element/action on the unchanged screen is blocked with
  `REPEATED_UNVERIFIED_BACKGROUND_ACTION`. A different action or a genuinely
  changed screen can proceed after a fresh INSPECT.
- During startup, `INSPECT` retries up to two times at 160 ms intervals
  only for windows not yet available; no blind tap retries occur.
- Helper error codes such as `HELPER_RESTART_REQUIRED` are returned unchanged
  to Live rather than being replaced by a generic disconnected error.
- Neither Android gesture dispatch nor changing semantic fingerprints are
  accepted as proof that the **whole user goal** succeeded.
- JVM test: `BackgroundPhoneSessionPolicyTest`; Android CI builds the APK.

### Local acceptance notes
Test with a non-sensitive screen. Launch a background App, INSPECT, tap a
low-risk UI control, and INSPECT again. Deliberately request the exact same
unverified action on the unchanged screen and verify it gets blocked. Check
that a new voice instruction cannot reuse an earlier token. After deliberately
exhausting the mutation budget, confirm HANDOFF and CLOSE still work. Continue
checking One UI's main-screen focus and task continuity on real hardware.


## Stage 5 — virtual-screen Vision Fallback (v1.8.118)

This experiment extends the existing **Gemini Live** video channel.
No second AI model or network endpoint is introduced. **User must enable
experimental background Live and explicitly ask for background operations.**

When semantic INSPECT reports `visionRecommended=true` or the user
explicitly asks to view the background, Gemini can call
`background_phone(action=VISION)`. Runtime:
- Verifies the Live session owns the exact virtual display and package;
- Allows only `com.apple.android.music` and `com.google.android.apps.maps`
  and requires trusted **focus-isolated** display mode (otherwise stops);
- Scans the **full** Accessibility tree for sensitive/credential and send
  controls before it transmits any image. If semantic windows are completely
  unavailable, it fails closed rather than sending an unclassified picture;
- Obtains one fresh, display-ID + package-bound JPEG from Shell; rejects
  stale/oversized/mismatched images and sends it as `realtimeInput.video`
  using the existing Live WebSocket. The JPEG is **never** placed in the
  tool response, diagnostic clipboard, or persisted on disk;
- Returns a fresh one-time `visionToken` and the virtual image dimensions.

If a desired **low-risk** button is missing from the semantic tree, Gemini
may request `background_phone(action=VISUAL_TAP,visual_token=...,
visual_x=...,visual_y=...,target=...)`. Normalized coordinates are 0–1000
on the **virtual** screenshot, not the physical screen. Execution requires:
same finalized user turn, live ownership, 8-second token, unchanged
Accessibility fingerprint and coarse screenshot comparison, a low-risk
semantic target, non-sensitive UI, exact display/package-bound shell tap,
and available mutation budget. The token is consumed even on failure.

After visual TAP, the result remains `STEP_PENDING`; Gemini must INSPECT
again. A changed fingerprint is *not* proof of the whole user goal.

**Vision frame budget:** three VISION captures per virtual App session;
the existing 18 INSPECT-or-VISION and 10 mutation limits still apply.
Install v1.8.118 and restart the ADB Shell Helper: protocol is now **v4**.

### Recommended quick S25 Ultra check

1. Use a non-sensitive Apple Music or Maps screen on the background display.
2. Say 「在背景螢幕打開 Apple Music，看看現在的畫面」.
3. After INSPECT, say 「在背景用畫面辨識找播放按鈕」.
4. Confirm the screenshot corresponds to the background App, not Crew
   Helper's visible Activity. Confirm nothing in the physical display
   moves when the background button is tapped.
5. Try 「在背景按播放」, observe fresh semantic evidence, then say
   「把背景的 App 移到主螢幕」.
6. Test no-helper, stale-helper, shared-focus, secure/login screen,
   changed-UI, expired token and out-of-bounds coordinates; all should
   fail safely rather than tapping the phone foreground.

Known limitation: Real-time video frame delivery is reused from the
existing Gemini Live channel; only real-device testing can prove the
model associates that image with the correct tool turn. Visual TAP is
intentionally conservative and may refuse animated screens or incomplete
Accessibility trees.


## 2026-11-03 — foreground App launch reliability / Mika architecture comparison

The existing ordinary `phone_action(OPEN_APP)` uses the foreground
Accessibility bridge (`/launch`), **not** the opt-in background
Shell virtual-display helper. This patch does not silently redirect normal
foreground actions to the virtual display or grant Shell permissions.

Improvements:
- Exact known App targets (Google Maps/地圖, Apple Music, Chrome, YouTube,
  explicit package IDs) are resolved by package instead of fuzzy localized
  launcher labels; arbitrary App names still use the installed App catalog.
- Android returns a distinct bounded launch result:
  `APP_NOT_FOUND`, `LAUNCH_INTENT_NOT_FOUND`, `LAUNCH_SECURITY_DENIED`,
  `LAUNCH_RUNTIME_EXCEPTION`, `LAUNCH_TIMED_OUT`, or
  `LAUNCH_MAIN_HANDLER_UNAVAILABLE`. It no longer discards all exceptions.
- A launch is verified only when Accessibility observes the **expected
  package** in the foreground. Screen motion or a different App is insufficient.
- The ADB local helper script verifies a running PID before reporting a
  successful start. `bash scripts/background_display_local.sh doctor`
  prints non-sensitive prerequisites and next steps.
- The Background Display experiment page can open Android developer options
  for user-authorized Wireless Debugging.

Manual checks:
1. Ask Live to open Google Maps, Apple Music, and one unfamiliar installed App.
   Confirm the **package** in the returned foreground observation.
2. Test an uninstalled App. Expect a meaningful failure, not blind repeated
   taps or a false claim of success.
3. Repeat with Accessibility disabled, a restricted App, or stale Helper.
   Check failure codes. Do not count an arbitrary changed screen as success.
4. Run `bash scripts/run_agent_runtime_tests.sh` and Android CI build.
5. On a Galaxy S25 Ultra, test foreground and virtual paths **separately**
   with physical-screen isolation.

**Not yet implemented:** Mika's complete in-app Wireless ADB pairing and
watchdog reattachment. Starting the Shell helper continues to require an
authorized ADB session and an explicit user action. Automatically enabling
Wireless Debugging or silently escalating a normal App's privileges would
violate the existing safety boundary. Real-device acceptance is still
required before claiming Mika-equivalent operation.


## Stage 6: Shizuku-assisted Shell Helper startup (v1.8.119)

Shizuku is an **alternative launcher** to USB ADB for the same
`BackgroundDisplayShell` process, not a new Gemini Live executor.
The existing private LocalSocket peer-UID check, virtual display ownership,
semantic control, Vision safety limits and handoff verification remain unchanged.

Official Shizuku: https://shizuku.rikka.app/download/
Shizuku API: https://github.com/RikkaApps/Shizuku-API

### Setup (Samsung One UI / Android 12+)

1. Install Shizuku v13+ from the official distribution.
2. Start the Shizuku service *using its own authorized Wireless debugging
   pairing flow* (usually requires Wi-Fi on non-root devices), or USB ADB.
   Crew Helper does **not** bypass pairing, silently enable debugging,
   run arbitrary shell strings, or grant itself access.
3. Install Crew Helper v1.8.119, open
   **Settings > Advanced > Background Display POC**.
4. Tap **查看 Shizuku 服務狀態**, then
   **透過 Shizuku 啟動背景 Helper**. Approve Shizuku permission for
   Crew Helper when prompted.
5. Confirm **SHIZUKU_HELPER_CONNECTED** before enabling Gemini Live
   background experiment. Then restart Live to refresh its tool catalog.
6. To test the cellular-only requirement, disconnect Wi-Fi and USB
   **after the Shizuku-started helper is online**. Gemini Live requires
   Internet access but works over 4G/5G; the local helper socket does
   not need an ongoing ADB connection.

**Important:** On an unrooted device, Shizuku is *not* a permanent
boot-persistent privilege grant. After reboot, Shizuku itself usually
needs to be started again (Wireless debugging or USB). This patch removes
the need to manually type the Crew Helper `app_process` command once
Shizuku is already running; it does not remove the Shizuku bootstrap.

### Implementation and failure isolation

- Uses `dev.rikka.shizuku:api/provider:13.1.5` and the recommended
  **Shizuku UserService**, not deprecated `Shizuku.newProcess`.
- The UserService checks Linux UID **2000** and exposes only
  `startHelper()` and `shellUid()` over Binder; no arbitrary command
  execution, file paths or process kill from the main App.
- It launches the same known `app_process` class with fixed arguments
  and the installed APK path resolved internally. A separate App-side
  socket probe confirms success: a spawned child process alone is
  not considered successful.
- If a valid helper is already running, it is reused. If an older
  incompatible helper is running, it is **not** killed automatically.
- Shizuku UserService connection is detached when closing this Settings
  page but the background helper is not stopped.
- Normal ADB launcher remains supported as backup. Shizuku features
  are initiated only by pressing the user-visible button.

### Local validation matrix

- Not installed/running: show `SHIZUKU_NOT_RUNNING` without request.
- Running but unapproved: Shizuku permission prompt.
- Denied: `SHIZUKU_PERMISSION_DENIED`; no helper launch.
- Shell UID mismatch or root backend: fail closed.
- Granted + not yet running: Shizuku starts helper and socket confirms.
- Helper already connected: no duplicate process.
- Old APK helper: diagnostic, no forced stop or ownership takeover.
- Disable Wi-Fi after successful start: use 4G/5G Gemini Live and verify
  that the helper socket continues to respond and does not affect the
  physical foreground.
- Reboot: confirm Shizuku is stopped and retry setup, documenting
  Samsung One UI background process behavior.


## Repair: Shizuku Binder relay for Shell Socket EACCES

The root issue was that App UID requests directly connected to the Shell
helper's abstract LocalSocket, which is rejected with EACCES on some Samsung
One UI devices before any JSON response. Shizuku permission alone did not
change the connecting UID.

The new transport is:

```text
Crew Helper app UID
      │ Shizuku UserService Binder (authorized app UID only)
      ▼
Shizuku UserService (shell UID 2000)
      │ LocalSocket (shell UID peer)
      ▼
BackgroundDisplayShell (UID-checked; protocol v5)
      │ existing virtual display tools
      ▼
App on virtual display
```

- Only the fixed operations `status/open/preview/tap/swipe/front/close`
  are allowed on the Binder interface, with argument validation and
  display/package ownership checks. No arbitrary shell commands are exposed.
- The UserService verifies the caller really is Crew Helper's Android UID;
  unknown UIDs fail closed.
- Large JPEG preview results travel through a
  `ParcelFileDescriptor` pipe. The Binder transaction never contains the
  multi-megabyte Base64 image.
- App requests prefer the Binder channel whenever it is connected;
  there is **no fallback to the app Shell Socket after Binder failure**.
  The old direct Socket route remains for installations deliberately using
  USB ADB without Shizuku.
- The Shell helper must be **restarted** after installing this code so its
  peer-UID check can accept the Shizuku UserService. An old process cannot
  be patched in place.
- Observe `transport=SHIZUKU_BINDER` and `protocolVersion=5` in the
  returned diagnostics. A successful spawn by itself is not proof of
  background control.
- GitHub main was still at v1.8.119 when this repair branch was prepared;
  a separate local v1.8.133 snapshot is not present in this GitHub branch.
  Reconcile/cherry-pick changes before producing an installer intended to
  replace a locally installed 1.8.133 APK.

### Device acceptance checks

1. Start Shizuku, open background experiment, request permission and use
   **透過 Shizuku 啟動背景 Helper**.
2. Confirm STATUS returns protocol 5 and `transport=SHIZUKU_BINDER`.
3. Open Apple Music on the virtual display; request a preview. Confirm that
   a large screenshot transfers without Binder transaction failure.
4. TAP and SWIPE only on the virtual display; verify the physical screen is
   unaffected. Then test handoff/close.
5. Disable Wi-Fi after Shizuku and the Shell helper are running. Verify
   status, preview and gestures still work over the local Binder/Socket relay
   while Gemini Live uses cellular data.
6. If permissions are missing, the helper is stale, or UID checks fail, the
   app must stop with a specific diagnostic rather than attempt display 0.


## Stage 7 — Mika-style direct Binder handoff (experimental)

Instead of sending every phone operation from App → Shizuku UserService →
Shell LocalSocket → Helper, the **Shell Helper directly returns a Binder to
Crew Helper**. Shizuku now only bootstraps its creation.

```text
User-authorized Shizuku → startHelperDirect(one-time nonce over stdin)
      ↓
Shell UID 2000 BackgroundDisplayShell
      ↓  protected ContentProvider handoff (nonce, APK path, UID/PID)
Crew Helper App holds IBackgroundDirectShell Binder
      ↓  executeCommand (ParcelFileDescriptor response stream)
Same Shell Helper executes status/open/preview/tap/swipe/front/close
```

**Trust contract:** the provider requires
`android.permission.INTERACT_ACROSS_USERS_FULL` (Shell UID),
and independently validates the Binder calling UID/PID, the claimed
UID/PID, the exact currently installed `base.apk` source path, a
cryptographically random **single-use 60-second** launch nonce, and a
live Binder round-trip `identity()` to the exact Shell process. The
Shell Binder accepts calls only from Crew Helper's UID. Unknown ops or
arguments are denied; no arbitrary shell command interface is exposed.

The Shell Helper links to the owner's App Binder token and closes its
virtual display when the App dies. It does not create any LocalSocket in
direct mode. An explicit old USB ADB launch without a nonce still
supports its legacy local socket as a fallback, but Shizuku never uses
that path. Protocol **v6** is used for direct Binder requests and their
response stream is size-bounded. Large previews travel through a
`ParcelFileDescriptor`, not a Binder string.

### Acceptance sequence (S25 Ultra)

1. Install the CI APK with this patch and open Shizuku, start its daemon.
2. Enable/background experiment and press **透過 Shizuku 啟動背景 Helper**.
3. Require **DIRECT_SHELL_BINDER_CONNECTED**.
4. Press **檢查 Helper**: require
   `transport=DIRECT_SHELL_BINDER`, `protocol=6`.
5. Open Apple Music *on the virtual display* and inspect its screenshot.
   Do one low-risk virtual button TAP. Ensure the main display never moves.
6. Test close/handoff. Disconnect Wi-Fi and confirm the same transport
   still works with Gemini Live over 4G/5G.
7. Force-stop/restart the App. A lost Binder is not task completion and
   cannot authorize further virtual actions without reattaching.

**Not claimed:** boot-persistent Shizuku without root, confirmed Samsung
One UI compatibility, or Mika's complete self-healing/restart watcher.
These require separate real-device acceptance. GitHub main still had
v1.8.119 at branch creation while the user's local installer was
reportedly v1.8.133; this patch deliberately does not rewrite local
version numbers.


### No legacy operation relay in Shizuku

The stage-7 handoff also removes the previous `relay(requestJson)`
method from `IBackgroundShizukuService`. Shizuku now exposes only
`shellUid()`, the legacy startup method and the authenticated
`startHelperDirect(nonce)`; the App's phone operation path does not
use Shizuku's Binder at all after the Shell Binder is attached.
This eliminates the redundant Shell UID → LocalSocket hop rather than
just leaving it unused.


## P0 direct Binder stability follow-up

Once Shizuku is authorized for a direct Shell Helper, the App marks its
in-process direct transport as **required**. A missing or dead direct Binder
now yields `DIRECT_SHELL_BINDER_NOT_ATTACHED` or
`DIRECT_SHELL_BINDER_LOST` instead of silently probing the legacy
App → Shell LocalSocket and resurfacing `SOCKET_CONNECT_EACCES`.

A Shell Binder response is read via Android `Os.poll` on its own
`ParcelFileDescriptor` with a **12-second cumulative deadline**. This
prevents a stalled Shell response from indefinitely blocking the
background-experiment worker. Pipe replies are limited to 2 MiB.
The Shell helper also accepts a maximum of two concurrent direct
requests, rather than spawning unlimited waiting threads. When busy,
the request fails closed.

The settings page performs direct Binder readiness checks before trying
to bootstrap again, and reports a direct-Binder-specific recovery hint
if the connection was lost. This is a transport stability improvement,
**not** a claim of Samsung S25 Ultra hardware compatibility.

### Acceptance

- Bootstrap through Shizuku: `transport=DIRECT_SHELL_BINDER`,
  `protocol=6`, then status and preview.
- Force-stop the Shell Helper: the next operation must return
  `DIRECT_SHELL_BINDER_LOST` without trying the legacy socket.
- Repeat startup after failure: confirm a fresh one-time nonce, and
  verify no active helper is replaced without authorization.
- Simulate a stalled Shell command: the App must return
  `DIRECT_SHELL_REPLY_TIMEOUT` and remain interactive.
- Verify Apple Music preview and one low-risk tap without movement on
  the physical display.
