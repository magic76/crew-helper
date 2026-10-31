# Crew Helper — Dual Engine: Standard + 3.8 Extended Thinking (experimental)

## Architecture

There are **two independent Gemini Live WebSockets** for *eligible* multi-step requests:

- Foreground: `gemini-3.8-live` owns the **only microphone**, **only audible output**, all tool declarations, and all Android Runtime actions.
- Background: `gemini-3.8-live-extended-thinking` with `thinkingLevel=MEDIUM` receives a bounded **text** request, *no microphone*, and **no tool declarations**. The background AUDIO output is **discarded**; `outputAudioTranscription` is collected as optional, read-only advice.

The Extended model does **not** support structured JSON output. Its voice transcript must be filtered as untrusted natural language. `turnComplete` ends a spoken utterance, not an Extended reasoning interaction; the planner accepts advice only on `interactionStatus=IDLE`.

## Safety and rollout

Settings → Gemini Live Model → **Dual Engine · Standard + 3.8 Extended Thinking (experimental)**; applies on the **next call**. Default remains **Standard 3.8 Live**. Change back to Standard for immediate rollback.

P0 sends only explicitly qualified multi-step **public music search/play** requests. Do not send notes, private chats, URLs, phone screenshots, credentials, addresses, payment requests, or message content to the background socket. Its setup includes no tools and it cannot access Android. Requests are not persisted; only aggregate diagnostic counters remain during the process. This uses the existing Gemini API key and makes an additional billable Live connection per eligible intent; simultaneous-connection limits depend on the account.

All background sessions have an 18-second ceiling and are cancelled immediately if the user starts another instruction or the call ends. Background failures, setup rejection, missing transcripts, and timeouts must **not** interrupt Standard Live or claim success.

Transcript advice is bounded to three short action-oriented tips and screened for unsafe or false-completion text. Advice is attached **once**, to a subsequent `phone_action` or `inspect_ui` result *only if* the intent generation/goal still match and it fits the existing model-facing payload byte budget. It cannot override Runtime `goal.next`, `requiredTool`, permissions, SEND guards, or fresh UI verification. If the task finishes before advice arrives, the advice is unused.

## Verification

Agent Inspector's Live pipeline shows `Background Thinking: started / ready / failed / delivered`; none of these counters imply successful phone actions. Use these A/B checks on a device:

1. Standard mode, request an explicit Crew Notebook note; verify `create_note`, real execution and persisted note.
2. Dual mode, restart call. Inspector should show the **main** setup as `gemini-3.8-live`, `thinking=OFF`; **background** uses a separate `gemini-3.8-live-extended-thinking` WebSocket only for eligible tasks.
3. Request "打開 Apple Music，找周杰倫的歌播放". The main socket must continue issuing real `phone_action` calls. The background planner may show `started`, then `ready` or `failed` depending on server quota, transcript delivery and timing; `delivered` only if a later tool result could include it.
4. Interrupt with a different intent and verify old plans never appear.
5. Disable connectivity, exhaust quota, or inject an empty/unsafe transcript: Standard still works, and the plan never creates a tool action.
6. Simple note, private message and navigation tasks must not trigger the background connection.
7. Measure mobile battery, data usage and cost before allowing wider rollout.

## Source documentation

- https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live-extended-thinking
- https://ai.google.dev/gemini-api/docs/live-api/capabilities
- https://ai.google.dev/api/live

**Status:** Source implementation and Java CI tests; no Gemini-key/on-device dual-socket acceptance test from GitHub.


## P1 — Think Supervisor / Live Executor (incremental)

All finalized intents in **Dual Engine** mode are now registered in the
process-local Supervisor ledger. Trivial/open-app, notes and private tasks
use the **fast path**: the foreground Standard Live begins immediately,
without starting or waiting for a second connection. They still receive
a deterministic `supervisor.completionRequirement` in subsequent tool
responses when an active task exists. This requirement is derived from
the goal type, *not* a hallucinated success claim.

For bounded, public **multi-step MEDIA:PLAY** requests the Supervisor
starts the Extended planning socket in parallel as before. Advice is now
phase-tagged (`PLAN` / `RECOVERY`) and includes a per-goal verification
contract. The foreground Live session has explicit instructions to use
available semantic advice but to check fresh Android Runtime evidence
before selecting tools or declaring completion.

When a public playback tool step actually fails, the Supervisor checks
an allowlist of Runtime error categories, plus the current intent
generation, tool name, user-confirmation requirement and terminal task
state. Exactly **one** bounded recovery-planning connection is allowed
for that intent. It replaces an obsolete initial plan; no private
screen, messages, addresses, coordinates, account data or raw exception
text are sent. The recovery request contains only the originally
approved public music request and a sanitized failure category.
The foreground executor **never blocks**, and will continue its
usual Runtime-owned recovery when Think is unavailable or late.

A background socket has **no tool declarations, microphone or audible
output**. Unlike a foreground tool-bearing Live session, this
tool-free planning socket may accept at least **two actionable safe
advisory steps** after a fully completed speech turn, even if its
server does not issue an explicit `IDLE`. It does not inject a new
`clientContent` into an ongoing foreground thinking turn. Advice is
delivered to the foreground only in a later valid tool response,
within the response budget and the same generation/goal. If it arrives
after the last tool, it is discarded; this is a documented remaining
limitation of P1.

### Operator acceptance checks

1. Switch to Dual Engine and start a **new** Live call. Say
   "打開 Google Maps". Expect the app to open without any new
   background planning connection; `Supervisor fast-path` increases.
2. In a new call say "打開 Apple Music，搜尋鄧紫棋的歌並播放".
   Expect foreground `toolCall` as usual and background
   `started > 0`, followed by `ready` or `failed`.
3. Trigger a *real* allowlisted, non-blocking public music tool failure.
   Expect `Supervisor recovery-requested=1`, never more than one per
   intent. If a later phone/inspect response has budget and the
   background is ready, it may carry `thinkingAdvice.mode=RECOVERY`.
4. Start a new user intent before recovery finishes. The old advice
   must never be delivered under the new generation/goal.
5. Try private messages, notes, addresses, and payment requests:
   no request data may be forwarded to background Think.
6. Compare task success, recovery rate, extra latency, battery and
   API usage against Standard-only. No efficacy claim can be made
   until real-device A/B results exist.

### Still not implemented

This is not a strict central planner that always blocks Live
until a Think plan arrives. Requiring such a wait would regress the
reliable 1–2 second app-open path and create privacy/cost concerns.
The next phase, if on-device results justify it, can add structured
plan stages, verified stage transitions and a bounded, explicit
hand-off for long-running cross-app tasks. Runtime remains the
execution authority regardless of mode.
