package com.crewpocket.helper;

public final class ModelStepGuidanceTest {
    public static void main(String[] args) {
        targetNotFoundSuggestsDifferentMethod();
        sendAuthorizationStops();
        machineReadableErrorSurvives();
        freeFormFailureIsNotLeaked();
        successfulTypeReturnsUsefulEffect();
        waitRequiresObservation();
        System.out.println("ModelStepGuidanceTest passed");
    }

    private static void targetNotFoundSuggestsDifferentMethod() {
        ModelStepGuidance.Guidance g = ModelStepGuidance.from(
                "phone_action", "FAILED", "TAP", "tap_screen",
                "UI_TARGET_NOT_FOUND", "", "", false);
        expect("TARGET_NOT_FOUND", g.reason);
        expect("TRY_DIFFERENT_METHOD", g.next);
        expect("STEP_FAILED", g.effect);
    }

    private static void sendAuthorizationStops() {
        ModelStepGuidance.Guidance g = ModelStepGuidance.from(
                "send_text", "FAILED", "", "send_text",
                "CURRENT_SCREEN_SEND_NOT_AUTHORIZED", "", "", false);
        expect("SEND_NOT_AUTHORIZED", g.reason);
        expect("STOP_AND_WAIT_FOR_USER", g.next);
    }

    private static void machineReadableErrorSurvives() {
        ModelStepGuidance.Guidance g = ModelStepGuidance.from(
                "phone_action", "FAILED", "TYPE", "type_text",
                "INPUT_NOT_FOCUSED", "", "", false);
        expect("INPUT_NOT_FOCUSED", g.reason);
    }

    private static void freeFormFailureIsNotLeaked() {
        ModelStepGuidance.Guidance g = ModelStepGuidance.from(
                "phone_action", "FAILED", "TAP", "tap_screen",
                "raw failure containing user data 123456", "", "", false);
        expect("UNCLASSIFIED_STEP_FAILURE", g.reason);
    }

    private static void successfulTypeReturnsUsefulEffect() {
        ModelStepGuidance.Guidance g = ModelStepGuidance.from(
                "phone_action", "DONE", "TYPE", "type_text",
                "", "", "", false);
        expect("TEXT_ENTERED", g.effect);
        expect("EVALUATE_GOAL", g.next);
        expect("TYPE", g.action);
    }

    private static void waitRequiresObservation() {
        ModelStepGuidance.Guidance g = ModelStepGuidance.from(
                "phone_action", "WAIT", "TAP", "tap_screen",
                "PENDING_VERIFICATION", "IN_PROGRESS", "", false);
        expect("OBSERVE_REQUIRED", g.reason);
        expect("OBSERVE", g.next);
        expect("WAITING", ModelStepGuidance.progressState("WAIT"));
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
}
