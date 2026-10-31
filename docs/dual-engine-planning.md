# Crew Helper — Dual Engine P0 (Experimental)

## Architecture

The **Dual Engine** setting uses exactly **one** Gemini Live socket: `gemini-3.8-live` is the sole microphone, spoken-response, and tool-calling owner. A *separate, text-only REST request* to `gemini-3.6-flash` (with `thinkingLevel=LOW`) may run concurrently for selected multi-step requests.

This is **not two Live sessions** and does **not** use `gemini-3.8-live-extended-thinking` for background planning. The extended Live model had confirmed tool-less `IDLE` events on device. The REST model supports thinking and structured JSON outputs.

## On/off and rollback

Settings → Gemini Live Model → **Dual Engine · Standard voice + background thinking (experimental)**. Applies only to the next call. **Standard 3.8 Live** remains the default and immediate rollback. The dual mode snapshots the selection when the Live client is created; no new DB or external account.

## P0 eligibility and privacy

Pilot: only explicit multi-step **public media playback searches** (e.g. "find artist's song and play"). No background calls for note-taking, messaging, private chats, account/settings actions, payments, deletion, raw screen snapshots, screenshots, or navigation/destinations. The client rejects requests with URLs, email-like text, multi-digit identifiers, sensitive keywords, or overly long input. The bounded eligible media request goes to the **same Gemini API key/provider** as Live, but through a second endpoint. There is no on-device persistence of the planning prompt or plan; only aggregate process-local counters.

Each eligible user intent starts at most one background planner call, without blocking the Live WebSocket thread. 3-second connect / 8-second read limits, bounded JSON body and response, and cancellation/connection disconnect on a new intent or call stop. The planner has no tool declarations and no Android access.

## Delivery and execution authority

- Output is structured JSON (`steps` with at most three short advisory items).
- Runtime rejects unsafe or oversized steps, and accepts a plan only for the **same current intent generation and goal**.
- If ready *at a subsequent* `phone_action` or `inspect_ui` tool response, advice is included once as `thinkingAdvice`, strictly within the pre-existing tool-response byte budget.
- Standard Live still chooses the next tool and the existing Runtime owns intent generation, SEND guard, duplicate suppression, fresh screen evidence and verification. The plan **cannot grant authorization or claim success**.
- If the REST endpoint fails, times out, is unavailable, or the plan arrives too late, **Standard Live proceeds unchanged**. The plan is not injected as a fabricated user utterance and never interrupts an active voice turn. If the task needs no second tool result, the optional plan may be unused.

## Debug and success criteria

Agent Inspector's *Live pipeline* includes `Background Thinking: started / ready / failed / delivered` (counts only; never content).

1. Start **Standard**, ask "在 Crew 記事本建立一則測試筆記"; verify `toolCall`, execution, tool response and actual note creation.
2. Select **Dual Engine**, end and restart Live, confirm Inspector's setup model remains `gemini-3.8-live`, with `thinking=OFF`.
3. Ask "打開 Apple Music，找周杰倫的歌播放". Ensure normal tool calls occur and planner stats show `started`; `delivered` can be zero when the plan arrives after the last eligible tool result.
4. Speak a new instruction before background work finishes. Old suggestions must not arrive in the new intent.
5. Disable network access to the REST planner or exceed its quota. Standard Live tools must continue unaffected.
6. For notes, SMS/LINE, private credentials, and addresses, confirm **planner started remains unchanged**.
7. Check action verification, user interruption, voice routing, and app foreground behavior on the real phone.

**No on-device or API-key acceptance test was run from GitHub**; CI tests cover the pure Java policy and the Android compilation path. This P0 is intended for a controlled A/B and not an automatic release.

Official source: https://ai.google.dev/gemini-api/docs/models/gemini-3.6-flash
