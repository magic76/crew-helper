# Crew Experience trigger invariants

- Plain successful tasks never qualify for Experience review.
- Runtime must first derive deterministic friction evidence.
- Strong friction (score >= 5) is immediately eligible for model compression.
- Medium friction (score >= 3) stays quiet on the first occurrence and becomes reviewable when the same deterministic rule key repeats.
- Friction signals are bounded structural/runtime facts such as failed recovery, extra inspect, or retry; no transcript, screenshot, typed value, or model guess is stored.
- Previous-task evidence must not be replayed and counted again by a later task.
- The model never decides whether a rule should be learned; Runtime owns candidate identity, friction score, eligibility, and confidence.
- Gemini only turns Runtime-qualified friction evidence into a concise lesson.
