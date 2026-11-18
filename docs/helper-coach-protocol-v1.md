# Helper Coach v1 — shared protocol (proposal)

Status: **v1 contract and Android implementation draft**. The implementation is opt-in and remains subject to CI and device verification; this document does not claim that the end-to-end flow has passed on a device.

## Goal
Crew Pocket acts as a teacher; Crew Helper owns Android permissions, executes actions, and reports evidence. Pocket may diagnose, suggest an existing skill, propose a new skill, or propose a source-code patch. Pocket never silently changes Helper's code, installs an APK, or executes privileged operations.

## Transport and trust
- Same-device loopback only (127.0.0.1); never bind to 0.0.0.0. Discover existing Helper connected-server/Termux bridge before choosing endpoint or port.
- Explicit user opt-in, per-install pairing secret stored in private app storage, request authentication, expiry, nonce/replay protection, request size limits and rate limiting.
- No raw passwords, OTPs, payment fields, account tokens, microphone recordings or unrestricted screenshots in coaching telemetry. Redact before transfer; opt-in for any screenshot.
- Helper Runtime is sole executor. Pocket outputs structured advice, never raw shell/ADB commands as executable instructions.

## Message envelope
All messages have: `protocol_version: 1`, `event_id`, `task_id`, `timestamp` (ISO 8601), `source` (`helper` or `pocket`), and `kind`.

### Helper -> Pocket
`task_observation`: `app_package`, `goal`, `step_index`, `ui_summary` (redacted), `available_actions` (Runtime-advertised action identifiers), `previous_action`, `result` (`success|failed|unknown`), `error_code`, `evidence_refs` (local ephemeral references).

`task_outcome`: `verified` boolean, `verification_method`, `duration_ms`, `skill_id` (optional), `evidence_refs`.

### Pocket -> Helper
`coach_advice`: `observation_event_id`, `diagnosis`, `suggested_action_id` (must be in available_actions), `expected_postcondition`, `confidence` (0..1), `expires_at`, `requires_user_confirmation`.

`skill_candidate`: `skill_id`, `app_package`, `preconditions`, `action_sequence` (only allowlisted Runtime actions), `postconditions`, `evidence_refs`, `status: candidate`.

`code_change_proposal`: `repository`, `diagnosis`, `reproduction_steps`, `test_plan`, `proposed_diff_ref` (PR or patch reference). No automatic merge or install.

## State machine
`observed -> diagnosed -> advice_proposed -> runtime_validated -> executed -> outcome_verified -> candidate_recorded`.
Failure, timeout, changed foreground app or expired advice -> `aborted` and re-observe. Never assume a tap implies success.

## Skill promotion
- Candidate skills remain inactive by default.
- Promotion requires repeated independent verified outcomes, explicit scope (package/version/UI signature), regression checks, and rollback.
- Do not use an unverified coach recommendation to bootstrap its own verification.
- Keep provenance (task ID, observations, model/role version, verification method). Do not equate a model's verbal claim with an Android state assertion.

## First end-to-end acceptance
1. Helper runs Google Maps task with existing Runtime; a controlled failure emits redacted `task_observation`.
2. Pocket receives authenticated event and suggests only a currently available Runtime action.
3. Helper validates preconditions, executes once, then independently checks the postcondition.
4. Both sides record same task ID; candidate stays inactive until promotion criteria.
5. Test invalid auth, stale/duplicate advice, unexpected action ID, UI change, sensitive-screen redaction, offline Pocket and timeout; Helper must continue functioning without Pocket.
6. Repeat for Apple Music, verifying playback state rather than only opening the app.

## Implementation gates
1. Inspect existing Helper Connected Server Mode, Crew Experience and Pocket bridge/role/memory; reuse existing interfaces.
2. Implement authenticated loopback event ingestion and read-only coaching first.
3. Add Runtime action allowlist and postcondition verification.
4. Add candidate skill storage and review UI.
5. Separately implement code-change proposals as PRs with tests and explicit approval, never direct APK mutation.

## Android bridge implementation

The Android implementation reuses the existing loopback Bridge on `127.0.0.1:8766`; it does not add another listening service. Pocket calls require the Helper Coach setting to be enabled and a per-install pairing secret. The secret is stored through the app's encrypted secret store and is separate from the existing Bridge token. Rotate it from Crew Experience to revoke the old pairing.

Every request carries:

```text
X-Coach-Timestamp: <epoch milliseconds>
X-Coach-Nonce: <16-128 characters from A-Z, a-z, 0-9, underscore, hyphen>
X-Coach-Signature: <lowercase hex HMAC-SHA256>
```

The signed canonical bytes are `UPPERCASE_METHOD + "\n" + exact request-target (including query) + "\n" + timestamp + "\n" + nonce + "\n" + lowercase_hex(SHA256(raw_body))`. Requests older than 60 seconds, reused nonces, invalid signatures, oversized bodies (over 32 KiB), and excess-rate requests are rejected. Coach traffic is loopback-only. The Bridge reads the exact body bytes before authentication; chunked coach requests are not accepted.

### Helper event feed

`GET /coach/v1/events?since_event_id=<last-event-id>&limit=20` returns `{protocol_version, events, has_more, next_cursor}`. `since_event_id` is exclusive. The bounded in-memory feed holds up to 128 events and is cleared when the process restarts. If a cursor has already aged out, Pocket receives the retained events from the beginning of the feed and should deduplicate by `event_id`.

The current `task_observation` wire fields are `protocol_version`, `event_id`, `task_id`, `timestamp`, `source`, `kind`, `app_package`, stable `goal` intent category, `step_index`, redacted structural `ui_summary`, `available_actions`, safe `previous_action`, `result`, normalized `error_code`, `evidence_refs`, and app-scoped `active_skills` summaries (skill ID, version, and allowlisted action sequence only). The current GoalIntent values are `MEDIA:PAUSE`, `MEDIA:NEXT`, `MEDIA:PREVIOUS`, `NAVIGATION:START`, `MEDIA:PLAY`, `MESSAGE:SEND`, `TEXT:TYPE_ONLY`, `SEARCH:RESULT`, and `APP:OPEN`; unclassified goals are empty. It does not send skill names or preconditions, the free-form user goal, transcript, UI labels, screenshots, search terms, or the local TAP target. The current V1 action IDs are only `inspect_ui` and `retry_previous_safe_tap`; the retry target remains local to Helper. Observations expire after 60 seconds and bind to the active task generation, app package, and Runtime UI fingerprint; the generation and fingerprint are validated locally and are not sent to Pocket.

The current `task_outcome` wire fields are `protocol_version`, `event_id`, `task_id`, `timestamp`, `source`, `kind`, `app_package`, `verified`, `verification_method`, `duration_ms`, `result`, `error_code`, and `evidence_refs`. A Pocket response or a TAP dispatch alone is never completion evidence. Verified outcomes may count toward matching inactive skill candidates only when the app package and candidate postcondition match exact trusted Runtime evidence.

`coach_action_result` is appended to the same event feed after an accepted advice reaches a terminal Runtime state. Its fields are `protocol_version`, `event_id`, `task_id`, `timestamp`, `source`, `kind`, `observation_event_id`, `advice_event_id`, `suggested_action_id`, `status` (`executed|failed|cancelled`), `runtime_action_success`, normalized `error_code`, and allowlisted `evidence_refs`. This reports the individual advised action; it does not claim that the user's overall goal completed. Pocket correlates this event by `advice_event_id` and waits for `task_outcome` on the same `task_id` to display terminal goal verification. A successful Runtime action can still have an unverified task outcome.

### Pocket request routes

All POST bodies use the v1 envelope fields `protocol_version`, `event_id`, `task_id`, `timestamp`, `source: "pocket"`, and `kind`.

* `POST /coach/v1/advice`: `coach_advice` adds `observation_event_id`, `diagnosis`, `suggested_action_id`, empty `parameters`, `expected_postcondition`, `confidence`, `expires_at` (ISO-8601 UTC), and `requires_user_confirmation`. V1 permits only `inspect_ui` and `retry_previous_safe_tap`. The former requires `FRESH_OBSERVATION`; the latter must match a retryable safe TAP and its goal-specific Runtime postcondition. Both are rejected if stale, duplicated, tied to a different task/app/UI, under 0.5 confidence, or marked as requiring confirmation. HTTP 202 includes the accepted `advice_event_id` and means queued for Runtime revalidation, not executed. Helper submits the action through the existing `ToolCallDispatcher`, Runtime preflight, safety checks, and normal verification path. A changed UI causes a fresh observation instead of executing the old advice. Poll the event feed for the correlated `coach_action_result`; use `task_outcome` for overall completion.
* `POST /coach/v1/skill-candidate`: stages an inactive candidate. Its action sequence can contain only the two V1 coaching action IDs. Candidates remain inactive until two distinct, trusted Runtime task outcomes match their package and declared postcondition, regression checks are explicitly confirmed in Crew Experience, and the user activates the skill. Deactivation and rollback are available in the same review UI.
* `POST /coach/v1/code-change-proposal`: stages a diagnosis, reproduction steps, test plan, and GitHub PR/commit reference for `magic76/crew-helper`. Inline patches, source code, APKs, shell commands, and automatic installs are rejected.

The Pocket agent should poll the event feed, retain/deduplicate `event_id`, reply only to current observations, and treat a 202 response as an acknowledgement. It should not send target labels or action arguments: the Helper retains the local retry target and chooses the exact Runtime tool mapping. If Pocket is offline or unpaired, the normal Gemini Live/Runtime path remains independent.
