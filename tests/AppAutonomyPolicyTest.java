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

        System.out.println("AppAutonomyPolicyTest passed");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
