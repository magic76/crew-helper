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

## Current P0 limits

This phase exports useful deterministic evidence, **not** a remote debugger,
a fully autonomous phone-control agent, audio recordings, screenshots, or a
replay engine. P1 can add a gated interactive ADB bridge and explicit user-owned
screen snapshots after P0 is validated on-device.
