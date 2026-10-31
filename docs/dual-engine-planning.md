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
