# Crew Agent Core

Provider-neutral Agent Harness extracted from Crew Helper.

This module intentionally does not know about Android Accessibility, teaching, stories, prompts for a specific product, or Gemini protocol details. Products supply an `AgentSpec`, a `ModelSession` adapter, and a `ToolRegistry`.

## Runtime loop

`user input -> ModelSession -> ModelEvent -> AgentHarness -> ToolRegistry -> ToolResult -> ModelSession`

## Crew Helper pilot

Crew Helper currently routes only the read-only `list_notes` tool through this harness. `NativeGeminiLiveClient` still owns the physical Gemini Live websocket/audio session, while `GeminiLiveToolSessionAdapter` attaches at the already-decoded function-call boundary. Notebook mutations and phone mutation tools remain on the existing runtime during this staged migration.

## Reuse plan

During extraction this module lives inside `crew-helper` so behavior can stabilize without breaking the other Crew apps. Once the API is stable, move the module unchanged to a standalone `crew-agent-harness` repository and publish it as `com.magic76.crew:agent-core`.

The real-time voice provider should be implemented outside this module (for example `agent-gemini-live`). Self-improvement/reasoning models should also use a separate model role/router instead of replacing the low-latency live session.
