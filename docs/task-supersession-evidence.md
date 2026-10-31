# Task completion and supersession evidence

## Purpose

When a new human utterance arrives, Crew Helper must not confuse
**Android action succeeded**, **Runtime verified a single step**, and
**the user's entire goal was fulfilled**. The prior Android build may
show every task as `SUPERSEDED_BY_USER` despite 20/20 successful tool
responses; successful tool transport is not terminal goal proof.

## New behavior

- Before accepting a new unrelated instruction, an active task with a
  **strong, exact, Runtime-verified terminal result** is finalized once.
  Current implemented goal/evidence pairs:
  `APP:OPEN / APP_OPEN_FOREGROUND_VERIFIED`,
  `NAVIGATION:START / MAPS_NAVIGATION_ACTIVE_VERIFIED`,
  `MEDIA:PLAY / MEDIA_UI_PLAYING or MEDIA_PLAYBACK_BECAME_ACTIVE`,
  `SEARCH:RESULT / SEARCH_RESULT_SCREEN_INSPECTED`, and
  `TEXT:TYPE_ONLY / TYPE_ONLY_USER_INTENT_SATISFIED`.
  A block, pending post-action inspection or unverified result prevents
  auto-finalization. Message sending, payments and unknown goals are
  intentionally never auto-completed here.
- `EVIDENCE_AVAILABLE / RUNTIME_V2_VERIFIED`, a successful tap, or
  `MEDIA_PLAY_CONTROL_VERIFIED` is only **step progress**. This
  never inflates a playback success score.
- For legitimately cancelled tasks, persist an enum-only
  `cancelProgressEvidence`: `VERIFIED_STEP_ONLY`,
  `EXECUTED_UNVERIFIED`, `NO_MUTATION` or `VERIFIED_GOAL`.
  Inspector and performance report the cancelled task's work separately
  from full-goal success. Existing cancellation categories, historical
  observations and evaluation denominator remain unchanged.
- The new policy is pure Java with deterministic regression tests wired
  to `scripts/run_agent_runtime_tests.sh`.

## Live diagnostics and limits

This intentionally does **not** alter Gemini Live turn parsing,
microphone gating, model selection, user authorization, tool retry policy
or Runtime verification. New finalized speech remains authoritative.
Speech echo or ASR misrecognition is not reliably identifiable from
`transcript confidence: not provided`; never suppress genuine
corrections or new requests based on a speculative echo heuristic.
`VERIFIED_STEP_ONLY` indicates something worked, not that the task
was completed. Compare real-device user perception and the new counters
before broadening the terminal evidence allowlist.

The current PR does not change Android versionName/versionCode. The
development build should be reconciled with the locally installed
1.8.117 source and signed/tested before distribution.
