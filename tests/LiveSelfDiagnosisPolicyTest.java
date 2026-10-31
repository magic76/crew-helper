package com.crewpocket.helper;

public final class LiveSelfDiagnosisPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        LiveSelfDiagnosisPolicy policy = new LiveSelfDiagnosisPolicy();
        policy.newIntent(7, "APP:OPEN");
        check(policy.offer(7, "打開 Google Maps",
                "com.apple.android.music", true) == null,
                "no diagnosis or recovery on a successful or unused intent");
        policy.toolReceived("private-id", 7, "phone_action", "TAP");
        policy.toolResponse("private-id", 7, false, "APP_OPEN_ACTION_REQUIRED");
        LiveSelfDiagnosisPolicy.Snapshot snapshot = policy.snapshot(7);
        check(snapshot.observedCalls == 1 && snapshot.failures == 1,
                "failed tool is correlated to the correct intent");
        check("APP_OPEN_ACTION_REQUIRED".equals(snapshot.failure),
                "only allowlisted failure codes are retained");
        LiveSelfDiagnosisPolicy.Option option = policy.offer(7,
                "打開 Google Maps", "com.apple.android.music", true);
        check(option != null
                && "phone_action".equals(option.tool)
                && "OPEN_APP".equals(option.action)
                && "Google Maps".equals(option.target),
                "an explicit app open can recover from a wrong TAP");
        check(policy.offer(7, "打開 Google Maps",
                "com.apple.android.music", true) == null,
                "only one option issuance per intent");
        check(!policy.consumeRecovery(7, "phone_action", "TAP"),
                "wrong recovery action cannot consume the lease");
        check(policy.consumeRecovery(7, "phone_action", "OPEN_APP"),
                "correct action consumes a single lease");
        check(policy.isRepeatedRecovery(7, "phone_action", "OPEN_APP"),
                "repeated chosen recovery must be blocked");
        check(!policy.consumeRecovery(7, "phone_action", "OPEN_APP"),
                "same recovery cannot execute again");
        check(!policy.isRepeatedRecovery(8, "phone_action", "OPEN_APP"),
                "recovery cannot leak to new generation");

        policy.newIntent(8, "APP:OPEN");
        policy.toolReceived("new-id", 8, "phone_action", "TAP");
        policy.toolResponse("new-id", 8, false, "APP_OPEN_ACTION_REQUIRED");
        check(policy.offer(8, "打開 Google Maps",
                "com.google.android.apps.maps", true) == null,
                "no recovery offered when target app already open");
        check(policy.offer(8, "打開某個 App",
                "com.apple.android.music", true) == null,
                "unrecognized app never guessed");
        check(policy.snapshot(7).failures == 0,
                "old generations cannot read new diagnosis");

        policy.newIntent(9, "MEDIA:PLAY");
        policy.toolReceived("tap-ambiguous", 9, "phone_action", "TAP");
        policy.toolResponse("tap-ambiguous", 9, false,
                "LOCATOR_MARGIN_TOO_SMALL");
        check(policy.offer(9, "播放鄧紫棋",
                "com.apple.android.music", false) == null,
                "no inspection recommendation when it is not allowed");
        LiveSelfDiagnosisPolicy.Option inspect = policy.offer(9,
                "播放鄧紫棋", "com.apple.android.music", true);
        check(inspect != null && "inspect_ui".equals(inspect.tool),
                "ambiguous TAP offers fresh read-only inspection");
        check(policy.consumeRecovery(9, "inspect_ui", ""),
                "read-only inspection consumes the lease");
        check(policy.isRepeatedRecovery(9, "inspect_ui", ""),
                "repeated recovery inspection is suppressed");

        policy.newIntent(10, "MEDIA:PLAY");
        policy.toolReceived("old", 10, "phone_action", "TAP");
        policy.newIntent(11, "APP:OPEN");
        policy.toolResponse("old", 10, false, "APP_OPEN_ACTION_REQUIRED");
        check(policy.snapshot(11).failures == 0,
                "late old tool response cannot poison new intent");
        policy.toolReceived("diagnostic", 11, "inspect_runtime_trace", "");
        policy.toolResponse("diagnostic", 11, false, "OTHER");
        check(policy.snapshot(11).failures == 0,
                "read-only diagnosis calls cannot create recovery triggers");
        policy.toolReceived("fresh", 11, "phone_action", "TAP");
        policy.toolResponse("fresh", 11, false, "caller-secret");
        check("OTHER".equals(policy.snapshot(11).failure),
                "unknown raw exception text never enters model-facing snapshot");
        policy.newIntent(12, "MEDIA:PLAY");
        policy.toolReceived("failed-tap", 12, "phone_action", "TAP");
        policy.toolResponse("failed-tap", 12, false,
                "LOCATOR_MARGIN_TOO_SMALL");
        policy.toolReceived("inspected", 12, "inspect_ui", "");
        policy.toolResponse("inspected", 12, true, "");
        check(policy.offer(12, "播放鄧紫棋",
                "com.apple.android.music", true) == null,
                "successful fresh evidence clears outdated recovery cause");

        policy.reset();
        check(policy.snapshot(-1).observedCalls == 0,
                "live session reset clears diagnostic state");
        System.out.println("LiveSelfDiagnosisPolicyTest passed " + checks + " checks");
    }

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }
}
