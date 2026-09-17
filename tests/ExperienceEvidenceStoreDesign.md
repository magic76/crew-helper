# Crew Experience trigger invariants

- Proven recovery evidence is immediately eligible for model compression.
- Routine successful semantic transitions do not call the model until the same deterministic rule has been observed 3 times.
- Later routine confirmations occur every 3 matching observations (3, 6, 9, ...).
- Previous-task evidence must not be replayed and counted again by a later task.
- The model never decides whether a rule should be learned; Runtime owns candidate identity, eligibility, and confidence.
- Gemini only turns Runtime-qualified evidence into a concise lesson.
