package com.crewpocket.helper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Source-level regression checks for Android-facing Gemini setup and tool
 * protocol, also runnable from the lightweight standalone Runtime test suite.
 */
public final class ExtendedThinkingIntegrationTest {
    private static int checks;

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static void require(boolean good, String label) {
        checks++;
        if (!good) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        String root = "app/src/main/java/com/crewpocket/helper/";
        String client = read(root + "NativeGeminiLiveClient.java");
        String catalog = read(root + "LiveToolCatalog.java");
        String config = read(root + "AppConfig.java");
        String main = read(root + "MainActivity.java");
        String boundary = read(root + "LiveHumanTurnBoundary.java");
        String coordinator = read(root + "AgentResponseCoordinator.java");

        require(client.contains("AppConfig.getLiveModel(this.appContext)"),
                "model selected once per session");
        require(client.contains("setup.put(\"model\", \"models/\" + liveModel)"),
                "setup selects explicit model");
        require(client.contains("generation.put(\"thinkingConfig\""),
                "extended reasoning config only when enabled");
        require(client.contains(".put(\"thinkingLevel\", \"LOW\")"),
                "extended uses supported LOW level");
        require(client.contains("extendedThinking);"),
                "passes extended mode into tool declarations");
        require(catalog.contains("LiveExtendedThinkingPolicy.toolBehavior(extendedThinking)"),
                "all exposed tools switch to NON_BLOCKING");
        require(client.contains("LiveExtendedThinkingPolicy.interactionComplete("),
                "task completion gated by interaction status");
        require(client.contains("if (frame.turnComplete) {") &&
                client.contains("if (interactionCompleted) {"),
                "utterance audio drain is separate from task completion");
        require(client.contains("if (!frame.serverPresent && !interactionCompleted) return;"),
                "standalone IDLE is still processed");
        require(boundary.contains("if (\"IDLE\".equals(status))"),
                "human turn classifier observes status-only IDLE");
        require(coordinator.contains("extendedThinking ? 45_000L"),
                "thinking response grace exceeds ordinary Live watchdog");
        require(config.contains("KEY_LIVE_MODEL") &&
                main.contains("showLiveModelDialog()"),
                "opt-in switch available in advanced settings");
        System.out.println("PASS ExtendedThinkingIntegrationTest: " + checks + " checks");
    }
}
