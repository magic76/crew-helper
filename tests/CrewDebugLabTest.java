package com.crewpocket.helper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;

public final class CrewDebugLabTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        CrewDebugTrace.setEnabled(false);
        CrewDebugTrace.intent(1L, "APP:OPEN");
        check(CrewDebugTrace.snapshot().totalEvents == 0L,
                "opt-out captures no events");

        CrewDebugTrace.setEnabled(true);
        CrewDebugTrace.intent(4L, "APP:OPEN");
        CrewDebugTrace.record("LIVE_TOOL_CALL", 4L, "secret-call-123",
                "phone_action", "TAP", "NONE");
        CrewDebugTrace.record("TOOL_QUEUED", 4L, "secret-call-123",
                "phone_action", "", "QUEUED");
        CrewDebugTrace.record("TOOL_RESPONSE", 4L, "secret-call-123",
                "phone_action", "", "FAILED");
        CrewDebugTrace.record("RUNTIME_GATE", 4L, "secret-call-123",
                "phone_action", "", "LOCATOR_MARGIN_TOO_SMALL");
        CrewDebugTrace.record("LIVE_TOOL_CALL", 4L,
                "contains_sensitive_user_data", "user raw PII",
                "REVEAL_SECRET", "raw-password");
        CrewDebugTrace.count("TOOL_CATALOG", 4L, "NONE", 16);
        CrewDebugTrace.record("NO_TOOL_TURN_COMPLETE", 4L,
                "", "NONE", "NONE", "NO_TOOL_CALL");
        CrewDebugTrace.duration(4L, "secret-call-123",
                "launch_app", 788L);
        CrewDebugTrace.record("TASK_FINISHED", 4L, "",
                "NONE", "NONE", "COMPLETED");
        CrewDebugTrace.record("TASK_CANCELLED", 4L, "",
                "NONE", "NONE", "SUPERSEDED_BY_USER");
        CrewDebugTrace.Snapshot snap = CrewDebugTrace.snapshot();
        check(snap.jsonl.contains("\"event\":\"TOOL_CATALOG\""),
                "declared tool count is recorded without tool descriptions");
        check(snap.jsonl.contains("\"event\":\"NO_TOOL_TURN_COMPLETE\""),
                "no-tool model turn is recorded as sanitized metadata");
        check(snap.jsonl.contains("\"count\":16"),
                "setup tool count uses bounded numeric metadata");
        check(snap.jsonl.contains("\"event\":\"TOOL_RUNTIME_DONE\""),
                "tool execution duration is included in sanitized timeline");
        check(snap.jsonl.contains("\"count\":788"),
                "duration is recorded as bounded milliseconds");
        check(snap.jsonl.contains("\"event\":\"TASK_FINISHED\""),
                "explicit completion is traced");
        check(snap.jsonl.contains("\"event\":\"TASK_CANCELLED\""),
                "supersession is traced");
        check(snap.toolCalls == 2, "counts calls");
        check(snap.responses == 1, "counts responses");
        check(snap.jsonl.contains("\"call\":\"c1\""),
                "pseudonyms are stable across events");
        check(!snap.jsonl.contains("secret-call-123"),
                "original call ID is never exported");
        check(!snap.jsonl.contains("contains_sensitive_user_data"),
                "arbitrary IDs never exported");
        check(!snap.jsonl.contains("REVEAL_SECRET")
                && !snap.jsonl.contains("raw-password")
                && !snap.jsonl.contains("user raw PII"),
                "unlisted text cannot escape the privacy allowlist");

        File root = Files.createTempDirectory("crew-debug-lab-test").toFile();
        File bundle = CrewDebugBundle.write(
                new File(root, "debug.zip"), snap, "1.8.115",
                "Live pipeline current process");
        try (ZipFile file = new ZipFile(bundle)) {
            for (String name : new String[]{
                    "session.json", "trace.jsonl", "runtime.json",
                    "screen.json", "diagnosis.md", "README.md"}) {
                check(file.getEntry(name) != null, name + " exists");
            }
            String exported = new String(
                    file.getInputStream(file.getEntry("trace.jsonl"))
                            .readAllBytes(), StandardCharsets.UTF_8);
            check(exported.equals(snap.jsonl), "ZIP has exact trace");
            check(!exported.contains("secret-call-123"),
                    "ZIP has no raw call ID");
        }

        for (int i = 0; i < 600; i++) {
            CrewDebugTrace.record("LIVE_FRAME", 4L, "",
                    "", "", "IN_PROGRESS");
        }
        CrewDebugTrace.Snapshot full = CrewDebugTrace.snapshot();
        check(full.dropped > 0, "bounded ring drops oldest events");
        check(full.jsonl.split("\\n").length <= 512,
                "ring never exceeds its capacity");
        CrewDebugTrace.setEnabled(false);
        check(!CrewDebugTrace.snapshot().enabled
                && CrewDebugTrace.snapshot().jsonl.isEmpty(),
                "turning off clears the capture");
        Files.deleteIfExists(bundle.toPath());
        Files.deleteIfExists(root.toPath());
        System.out.println("PASS CrewDebugLabTest: " + checks + " checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
