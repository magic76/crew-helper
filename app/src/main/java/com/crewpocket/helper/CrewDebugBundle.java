package com.crewpocket.helper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A small self-contained artifact for a local debugging agent. No uploads. */
final class CrewDebugBundle {
    private CrewDebugBundle() {}

    static File write(File destination, CrewDebugTrace.Snapshot trace,
            String appVersion, String pipelineReport) throws Exception {
        if (trace == null || !trace.enabled || trace.startedAt <= 0L) {
            throw new IllegalStateException("Enable Debug Lab before exporting");
        }
        File parent = destination.getParentFile();
        if (parent == null) throw new IllegalArgumentException("No export directory");
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create export directory");
        }
        String version = safeBuild(appVersion);
        String session = "{\"schema\":1,\"sessionId\":\""
                + json(trace.sessionId) + "\",\"appVersion\":\""
                + version + "\",\"model\":\"gemini-3.8-live-extended-thinking\""
                + ",\"startedAtEpochMs\":" + trace.startedAt
                + ",\"latestGeneration\":" + trace.generation
                + ",\"latestGoal\":\"" + trace.goal + "\""
                + ",\"eventCount\":" + trace.totalEvents
                + ",\"droppedOldestEvents\":" + trace.dropped
                + ",\"privacy\":\"only allowlisted events; no text/audio/screen/call IDs\"}\n";
        String runtime = "{\"toolCalls\":" + trace.toolCalls
                + ",\"toolResponses\":" + trace.responses
                + ",\"cancelledQueued\":" + trace.cancellations
                + ",\"pipelineSummary\":\"" + json(pipelineReport)
                + "\"}\n";
        String diagnosis = diagnosis(trace);
        String screen = "{\"captured\":false,\"reason\":\"screen capture"
                + " and accessibility labels not collected in default Debug Lab\"}\n";
        String readme = "# Crew Debug Lab (schema 1)\n\n"
                + "Files are generated locally and shared only by user action.\n"
                + "The recorder keeps at most 512 events in RAM; when events are"
                + " dropped the timeline is incomplete.\n"
                + "Join events by generation and anonymized call reference.\n"
                + "No raw transcript, audio, UI labels, screenshots, tool arguments,"
                + " original call IDs, keys, addresses or messages.\n"
                + "diagnosis.md is a deterministic first pass, not verified root cause.\n";
        try (ZipOutputStream zip = new ZipOutputStream(
                new FileOutputStream(destination))) {
            writeEntry(zip, "session.json", session);
            writeEntry(zip, "trace.jsonl", trace.jsonl);
            writeEntry(zip, "runtime.json", runtime);
            writeEntry(zip, "screen.json", screen);
            writeEntry(zip, "diagnosis.md", diagnosis);
            writeEntry(zip, "README.md", readme);
        }
        return destination;
    }

    private static String diagnosis(CrewDebugTrace.Snapshot trace) {
        String kind;
        String detail;
        if (trace.generation < 0L) {
            kind = "NO_FINALIZED_INTENT";
            detail = "Capture contains no finalized user command. Reproduce the command before exporting.";
        } else if (trace.dropped > 0) {
            kind = "PARTIAL_TRACE";
            detail = "Oldest events were dropped. The missing prefix may contain the first decisive action.";
        } else if (trace.toolCalls == 0) {
            kind = "MODEL_NO_TOOL_CALL_OBSERVED";
            detail = "No function call appears in the captured timeline. "
                    + "Check transcription, model output, and Live status before blaming Android Runtime.";
        } else if (trace.responses < trace.toolCalls - trace.cancellations) {
            kind = "TOOL_RESPONSE_GAP";
            detail = "There are more tool calls than reported responses. "
                    + "Some may still be in flight, or the connection may have stopped before replies.";
        } else if (trace.jsonl.contains("\"event\":\"RUNTIME_GATE\"")) {
            kind = "RUNTIME_GATE_OBSERVED";
            detail = "At least one runtime gate intervened. "
                    + "Review gate codes per call before changing a model prompt.";
        } else {
            kind = "INSUFFICIENT_EVIDENCE";
            detail = "No uniquely attributable failure was proven by the event counters. "
                    + "Review the trace order and correlate a real device test.";
        }
        return "# Initial diagnosis (not proof)\n\n"
                + "Classification: `" + kind + "`\n\n"
                + detail + "\n\n"
                + "Events observed: " + trace.totalEvents
                + "; oldest events dropped: " + trace.dropped
                + "; tools: " + trace.toolCalls
                + "; responses: " + trace.responses
                + "; cancelled queued: " + trace.cancellations + ".\n\n"
                + "Do not mark a task successful solely because a tool response was sent. "
                + "Seek explicit runtime verification or another fresh screen observation.\n";
    }

    private static void writeEntry(ZipOutputStream zip,
            String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String safeBuild(String value) {
        if (value == null || !value.matches("[0-9A-Za-z._-]{1,40}")) {
            return "UNKNOWN";
        }
        return value;
    }

    private static String json(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c == '\n') result.append("\\n");
            else if (c == '\r') result.append("\\r");
            else if (c == '\t') result.append("\\t");
            else if (c < 32) result.append(String.format("\\u%04x", (int)c));
            else result.append(c);
        }
        return result.toString();
    }
}
