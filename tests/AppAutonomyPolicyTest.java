package com.crewpocket.helper;

public final class AppAutonomyPolicyTest {
    public static void main(String[] args) {
        expect(
                AppAutonomyPolicy.maySelfResolve(
                        true, "開始 navigation start"),
                "trusted navigation should self-resolve");
        expect(
                AppAutonomyPolicy.maySelfResolve(
                        true, "路線 directions"),
                "trusted directions should self-resolve");
        expect(
                !AppAutonomyPolicy.maySelfResolve(
                        false, "開始 navigation start"),
                "untrusted app keeps normal ambiguity policy");
        expect(
                !AppAutonomyPolicy.maySelfResolve(
                        true, "付款 payment"),
                "payment never bypasses safety");
        expect(
                !AppAutonomyPolicy.maySelfResolve(
                        true, "取消行程"),
                "trip cancellation never bypasses safety");
        expect(
                !AppAutonomyPolicy.maySelfResolve(
                        true, "cancel booking"),
                "booking cancellation never bypasses safety");
        expect(
                !AppAutonomyPolicy.maySelfResolve(
                        true, "修改帳號"),
                "account change never bypasses safety");

        expect(
                AppAutonomyPolicy.mayRecoverWithoutObservation(
                        true, "tap_screen", "開始 navigation"),
                "trusted low-risk tap can recover without inspect");
        expect(
                AppAutonomyPolicy.mayRecoverWithoutObservation(
                        true, "search_current_app", "大皇宮"),
                "trusted search can recover without inspect");
        expect(
                !AppAutonomyPolicy.mayRecoverWithoutObservation(
                        true, "send_text", "hello"),
                "message send never gains trusted recovery bypass");
        expect(
                !AppAutonomyPolicy.mayRecoverWithoutObservation(
                        true, "tap_screen", "取消行程"),
                "sensitive tap still requires safety handling");

        System.out.println("AppAutonomyPolicyTest passed");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
