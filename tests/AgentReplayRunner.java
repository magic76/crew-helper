package com.crewpocket.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Small dependency-free replay runner.  Fixtures contain only structural UI
 * evidence and optional redacted intent labels; never store typed/message text.
 */
public final class AgentReplayRunner {
    private static int passed;
    private static int total;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "tests/replay");
        if (!dir.isDirectory()) throw new IllegalArgumentException("Replay directory missing: " + dir);

        List<File> files = new ArrayList<File>();
        File[] listed = dir.listFiles();
        if (listed != null) {
            for (File file : listed) {
                if (file.isFile() && file.getName().endsWith(".replay")) files.add(file);
            }
        }
        Collections.sort(files);
        if (files.isEmpty()) throw new AssertionError("No .replay fixtures found in " + dir);

        for (File file : files) run(file);
        System.out.println("PASS AgentReplayRunner: " + passed + "/" + total + " fixtures");
    }

    private static void run(File file) throws Exception {
        total++;
        Properties p = new Properties();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
        try { p.load(reader); } finally { reader.close(); }

        String runtime = required(p, "runtime");
        ActionTransaction.ExpectedEffect expected = ActionExpectation.forRuntimeAction(runtime);
        ActionObservation before = observation(p, "before");
        ActionObservation after = observation(p, "after");
        ExecutionEvidence execution = new ExecutionEvidence(
                bool(p, "execution.accepted"),
                bool(p, "execution.runtimeVerified"),
                bool(p, "execution.blocked"),
                bool(p, "execution.cancelled"),
                bool(p, "execution.allowDelayedUi"),
                p.getProperty("execution.errorCode", ""));

        ActionVerificationResult result = ActionVerifierV2.verify(
                runtime, expected, execution, before, after);

        String expectedStatus = required(p, "expect.status");
        String expectedCode = p.getProperty("expect.code", "").trim();
        boolean ok = expectedStatus.equals(result.status.name())
                && (expectedCode.isEmpty() || expectedCode.equals(result.code));
        if (!ok) {
            throw new AssertionError(file.getName() + " expected " + expectedStatus + "/"
                    + expectedCode + " but got " + result.status + "/" + result.code);
        }
        passed++;
        System.out.println("  PASS " + file.getName() + " -> " + result.status + " / " + result.code);
    }

    private static ActionObservation observation(Properties p, String prefix) {
        boolean available = boolDefault(p, prefix + ".available", true);
        if (!available) return ActionObservation.unavailable();
        return new ActionObservation(
                true,
                p.getProperty(prefix + ".package", ""),
                p.getProperty(prefix + ".fingerprint", ""),
                p.getProperty(prefix + ".stable", ""),
                p.getProperty(prefix + ".focus", ""),
                p.getProperty(prefix + ".focusRole", ""),
                integer(p, prefix + ".elements", 20),
                System.currentTimeMillis());
    }

    private static boolean bool(Properties p, String key) {
        return Boolean.parseBoolean(p.getProperty(key, "false"));
    }

    private static boolean boolDefault(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int integer(Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing fixture key: " + key);
        return value;
    }
}
