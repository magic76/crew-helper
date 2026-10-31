# Crew Helper · Gemini 3.8 Live Extended Thinking P0

**Scope:** Experimental, opt-in only. Normal Gemini 3.8 Live remains the default. This implementation belongs to `magic76/crew-helper` (Android phone actions and Voice Debug), not Crew Pocket's Termux chat UI.

## Model selection

Open **Settings → Advanced → Live model (experimental)** and choose **Gemini 3.8 Live Extended Thinking · Low**. The choice applies **to the next call**, not an active Live session. Switch back to **3.8 Live · Standard** for immediate rollback; no DB migration or credentials are changed.

## Protocol adjustments

- Model: `models/gemini-3.8-live-extended-thinking`, with `generationConfig.thinkingConfig.thinkingLevel=LOW` only for Extended Thinking.
- Tool declarations: `NON_BLOCKING` **only** for Extended Thinking; original `BLOCKING` retained for ordinary Live. `ToolCallDispatcher` still serializes actual Android mutations, validates intent generation and tool result, and preserves sensitive-action authority.
- Audio boundary: `turnComplete` drains the spoken utterance but **does not** end an Extended Thinking interaction.
- Task completion: requires explicit `interactionStatus=IDLE` (including status-only frames) and no queued/in-flight Runtime tool calls. `IN_PROGRESS` is not task completion. No inference from silence or plain `turnComplete`.
- Agent response watchdog: Extended Thinking allows 45 seconds of background work after tool results, versus ordinary Live's original 12 seconds. This is not an unlimited wait; original safety and cancellation remain in effect.
- The root and nested Gemini status aliases (`interactionStatus`, `interaction_status`) are already decoded in `GeminiLiveTurnHandler`.

## Manual acceptance checks (requires a phone and a configured Gemini API key)

1. With Standard selected, ask Crew to open Apple Music, search a song and play it. Verify the existing behavior and hardware voice interruption remain unchanged.
2. Enable Extended Thinking, **start a new call**, say the same request and examine Voice Debug. A filler narration + `turnComplete` must not finish the task; a later `phone_action` / `inspect_ui` must still be dispatched. Wait for the final `IDLE` to mark completion.
3. Test a background action with `IN_PROGRESS`, a tool response and a separate final `IDLE` server frame; verify no early success and no stale follow-up action.
4. Say a different instruction mid-task, then cancel; the intent-generation safety gate must reject old tools.
5. If the server rejects setup, switch to Standard and start a **new** call. Neither model is auto-switched behind the user's back.

CI coverage: `scripts/run_agent_runtime_tests.sh` includes `LiveExtendedThinkingPolicyTest` and `ExtendedThinkingIntegrationTest`. Production-device timing, API acceptance and tool/voice behavior are **not** proven by Java unit tests. The API may enforce plan/project-specific model availability.

Official references:
- https://ai.google.dev/gemini-api/docs/live-api/thinking
- https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live-extended-thinking
