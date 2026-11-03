package com.crewpocket.helper;

public final class BackgroundBootstrapPolicyTest {
    private static void expect(BackgroundBootstrapPolicy.State expected,
                               boolean direct, boolean hadDirect,
                               boolean shizuku, boolean authorized, String caseName) {
        BackgroundBootstrapPolicy.State actual =
                BackgroundBootstrapPolicy.classify(direct, hadDirect, shizuku, authorized);
        if (actual != expected) throw new AssertionError(caseName + ": " + actual);
    }
    public static void main(String[] args) {
        expect(BackgroundBootstrapPolicy.State.DIRECT_BINDER_ALIVE,
                true, true, false, false, "Wi-Fi off but direct Binder alive");
        expect(BackgroundBootstrapPolicy.State.DIRECT_BINDER_ALIVE,
                true, false, true, false, "direct Binder precedes Shizuku checks");
        expect(BackgroundBootstrapPolicy.State.SHIZUKU_BOOTSTRAP_READY,
                false, false, true, true, "Shizuku ready to bootstrap");
        expect(BackgroundBootstrapPolicy.State.SHIZUKU_PERMISSION_REQUIRED,
                false, false, true, false, "Shizuku authorization required");
        expect(BackgroundBootstrapPolicy.State.DIRECT_BINDER_LOST_BOOTSTRAP_NEEDED,
                false, true, false, false, "Direct helper lost after Wi-Fi off");
        expect(BackgroundBootstrapPolicy.State.BOOTSTRAP_NEEDED,
                false, false, false, false, "5G-only first start cannot obtain shell");
        System.out.println("BackgroundBootstrapPolicyTest OK");
    }
}
