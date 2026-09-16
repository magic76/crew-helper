# Crew Agent Core

Provider-neutral Agent Harness extracted from Crew Helper.

This module intentionally does not know about Android Accessibility, teaching, stories, prompts for a specific product, or Gemini protocol details. Products supply an `AgentSpec`, a `ModelSession` adapter, and a `ToolRegistry`.

## Runtime loop

`user input -> ModelSession -> ModelEvent -> AgentHarness -> ToolRegistry -> ToolResult -> ModelSession`

## Crew Helper pilot

Crew Helper now routes the read-only Notebook tools `get_note`, `search_notes`, and `list_notes` through one session-level shared harness. `NativeGeminiLiveClient` still owns the physical Gemini Live websocket/audio session, while `GeminiLiveToolSessionAdapter` attaches at the already-decoded function-call boundary. Notebook mutations and phone mutation tools remain on the existing runtime during this staged migration.

The pilot path is:

`Gemini Live function call -> NotebookToolHandler -> GeminiLiveToolSessionAdapter -> AgentHarness -> ToolRegistry -> NoteStore -> ToolResult -> existing Gemini tool response`

`ReadOnlyNotebookHarness` is created once with `NotebookToolHandler` and reused for all read-only Notebook calls. It listens to normalized `AgentEvent` values for low-content tracing (event type, tool, call id and success/failure only; note content is not written to the trace).

CI runs pure-Java `agent-core` contract tests, the existing Agent Runtime replay tests, and the Android APK/AAB build.

## Reuse plan

During extraction this module lives inside `crew-helper` so behavior can stabilize without breaking the other Crew apps. Once the API is stable, move the module unchanged to a standalone `crew-agent-harness` repository and publish it as `com.magic76.crew:agent-core`.

Crew Mate can consume the same runtime by providing a Mate-specific `AgentSpec`, communication/approval tools, and product state. Conversation visibility, recipient selection, draft approval, and send authorization remain Mate-owned policy rather than being hard-coded into `agent-core`.

The real-time voice provider should be implemented outside this module (for example `agent-gemini-live`). Self-improvement/reasoning models should also use a separate model role/router instead of replacing the low-latency live session.
