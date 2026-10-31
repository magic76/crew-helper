# Crew Debug Lab — P0

Crew Helper's **opt-in** incident trace lets a local coding agent identify the
first failing boundary rather than guessing from persisted Inspector snapshots.
No worker, backend, external analytics, Gemini call, or database is involved.

## Capture on the phone

1. Open **Crew Experience / Agent Inspector**.
2. Tap **Debug Lab: OFF** to turn it on. This clears the previous in-memory
   capture. Voice Debug is separate and does not need to be enabled.
3. Reproduce exactly one problem, e.g. **打開 Google Maps**.
4. Return to Inspector and tap **Export Debug ZIP**. Use Android's share sheet
   to send the file to your computer or the chat.
5. When finished, tap **Debug Lab: ON** to disable and clear the buffer and
   previously exported file. **Clear diagnostics** also resets the buffer.

It is normal for Inspector's older task snapshot to remain visible.
The new Debug Lab section identifies the current capture separately.

## Inspect with the local agent

```sh
python3 scripts/diagnose_debug_bundle.py crew-debug-lab.zip \
    --out crew-debug-analysis.md
```

For **debuggable APKs only** and a connected, authorized ADB device, export the
ZIP once in the app and pull it without opening the share sheet. The simplest
local-agent command is:

```sh
bash scripts/collect_crew_debug.sh
# Output: debug-output/crew-debug-lab.zip and crew-debug-analysis.md
```

Or run the underlying ADB commands manually:

```sh
adb exec-out run-as com.crewpocket.helper \
    cat cache/crew_debug_lab/latest.zip > crew-debug-lab.zip
python3 scripts/diagnose_debug_bundle.py crew-debug-lab.zip
```

`run-as` intentionally fails on release (non-debuggable) builds; use Android
sharing in those cases. Do not add a public localhost server to the app.

## ZIP structure and schema v1

- `session.json`: session UUID, app version, model, last intent/generation,
  capture boundaries and dropped event count
- `trace.jsonl`: up to 512 sequenced events with elapsed time (ms), user
  generation, anonymized call reference, tool/action enum and reason code
- `runtime.json`: safe Live pipeline counts and stage summary
- `screen.json`: explicit **not captured** indicator, so no UI text leaks
- `diagnosis.md`: deterministic preliminary classification
- `README.md`: bundle privacy/schema notes

All fields are allowlisted. **Never include** free-form tool arguments, user
utterances, replies, raw function-call IDs, arbitrary errors, tokens, screenshots,
private app UI labels or microphone audio. Debug Lab does not enable
AudioIncidentRecorder (whose separate debug-build behavior stays unchanged).

This is an in-memory ring buffer, not an exhaustive persistent flight recorder:
after a process restart or more than 512 events, earlier events cannot be
reconstructed. `droppedOldestEvents` must be considered during diagnosis.

## AI coding-agent procedure

1. Confirm the running APK's build number and Git commit. GitHub branch
   `investigation/apple-music-runtime-trace` may not match the local working tree.
2. Parse the ZIP with `scripts/diagnose_debug_bundle.py`. Review full event
   ordering grouped by `generation` and anonymized `call`.
3. Separate **observed fact** from **hypothesis**. Use labels:
   `MODEL_NO_TOOL_CALL`, `WRONG_TOOL_ACTION`,
   `RUNTIME_GATE`, `TOOL_RESPONSE_TRANSPORT_FAILED`,
   `TOOL_RESPONSE_MISSING`, `TOOL_FAILED`,
   `PARTIAL_TRACE`, `INSUFFICIENT_EVIDENCE`.
4. Locate the earliest unexpected boundary in
   `NativeGeminiLiveClient`, `ToolCallDispatcher`, semantic action routing or
   Android Runtime. Do not assume a historical TAP failure describes this run.
5. Add a minimal failing test or deterministic replay. Fix the identified
   boundary, run `scripts/run_agent_runtime_tests.sh`, and compare the next
   bundle. If evidence is insufficient, request a narrower reproduction.
6. Never automatically execute sensitive Android actions or upload the bundle.
   User authorization is required for any access beyond local analysis.


## P0.5 — Gemini Live 3.8 self-diagnosis

Two read-only tools are exposed in the normal phone-assistant mode:

- `inspect_runtime_trace`: a current-finalized-intent-only summary of the
  latest sanitized Runtime tool/action, bounded failure code, generation,
  counts, and (only when Debug Lab is enabled) at most eight allowlisted
  event records. No transcript, call ID, screen content, API key or raw error.
- `get_recovery_options`: at most one deterministic low-risk suggestion for
  the same generation. Currently supports explicit named-app `OPEN_APP`
  after `APP_OPEN_ACTION_REQUIRED`, or fresh `inspect_ui` when a TAP is
  ambiguous / search has no query. It never executes a phone operation.

Regular `phone_action` or `inspect_ui` is still required to perform the
suggested step. It must pass the existing voice permission and Runtime
preflight checks. The one-shot recovery policy rejects a repeated identical
recovery action for the same user generation, and resets on a new finalized
user intent. Successful tool responses do not request additional diagnostic
model calls; a short hint is attached to failed responses only.

**Limits:** if the model emits no tool calls at all, these model tools also
cannot run; the independent Android Debug Lab plus the offline analyzer
remain the source of evidence. A model explanation is a hypothesis, not proof
that the requested Android outcome was verified.

## Verified app-open completion and latency evidence

A finalized standalone `APP:OPEN` intent now closes the Agent task
immediately when `launch_app` succeeds, the post-action Accessibility
observation is fresh, the reported launched package equals the observed
foreground package, and enforced Runtime V2 verification is `VERIFIED`.
An `EVIDENCE_AVAILABLE` state alone is **never** terminal. Navigation,
search, and media-play goals are explicitly excluded from this fast finish.

Common spoken variants such as 「幫我開地圖」 and 「啟動 Apple Music」 now
map to `APP:OPEN`, but a compound 「搜尋／導航／播放」 request retains its
original higher-priority goal. The known-app recovery matcher uses the same
goal parser to prevent conflicting interpretations.

For new captures, events include `TOOL_RUNTIME_DONE` (its integer
`count` field represents clamped execution milliseconds), plus
`TASK_FINISHED` and `TASK_CANCELLED` with allowlisted reason codes.
This distinguishes time before the model's first tool from Android execution
and from waiting to close the task. It does not remove the time Gemini spends
deciding which first tool to emit; any direct app-open execution before the
model must be implemented separately with existing Runtime authorizations.

## Extended Thinking: intermediate speech must not interrupt reasoning

Gemini 3.8 Live Extended Thinking may emit `turnComplete` for intermediate
speech while `interactionStatus=IN_PROGRESS`. The client must continue
listening for async `toolCall` events until the authoritative `IDLE`
boundary. An outgoing `clientContent.turnComplete=true` **interrupts**
ongoing model generation, so the previous premature no-tool nudge after
intermediate speech has been removed. The diagnostic event
`NO_TOOL_TURN_COMPLETE` is retained but **never** grants tool authority.
A named-app nudge remains possible only at `IDLE`, once per intent.

The setup also records `TOOL_CATALOG` and `PHONE_TOOL_DECLARED` (0/1)
without logging actual tool descriptions or user speech, to distinguish
configuration failure from model skipping an available function.

Test standard `gemini-3.8-live` (omit `thinkingConfig`) against
`gemini-3.8-live-extended-thinking` (start with `LOW`) with exactly the same
minimal `NON_BLOCKING` diagnostic function and finalized typed input.
A persistent zero-tool result in Extended alone may be model/service-specific
rather than an Android Runtime bug. Always confirm whether the installed APK
actually includes the tested GitHub branch.

## Current P0 limits

This phase exports useful deterministic evidence, **not** a remote debugger,
a fully autonomous phone-control agent, audio recordings, screenshots, or a
replay engine. P1 can add a gated interactive ADB bridge and explicit user-owned
screen snapshots after P0 is validated on-device.
