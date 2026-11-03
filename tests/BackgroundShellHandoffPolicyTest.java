package com.crewpocket.helper;

public final class BackgroundShellHandoffPolicyTest {
    private static void expect(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        String apk = "/data/app/~~xyz/com.crewpocket.helper-123/base.apk";
        String nonce = "abcdefghi1234567890ABCDEFGHI1234567890";
        expect(BackgroundShellHandoffPolicy.check(2000, 432, 2000, 432,
                apk, apk, nonce, nonce, 5000, 4950, 60000), "valid shell");
        expect(!BackgroundShellHandoffPolicy.check(2001, 432, 2001, 432,
                apk, apk, nonce, nonce, 5000, 4950, 60000), "not shell");
        expect(!BackgroundShellHandoffPolicy.check(2000, 432, 2000, 434,
                apk, apk, nonce, nonce, 5000, 4950, 60000), "spoof pid");
        expect(!BackgroundShellHandoffPolicy.check(2000, 432, 2000, 432,
                apk, "/data/app/other/base.apk", nonce, nonce, 5000, 4950, 60000), "wrong apk");
        expect(!BackgroundShellHandoffPolicy.check(2000, 432, 2000, 432,
                apk, apk, nonce, "no", 5000, 4950, 60000), "bad nonce");
        expect(!BackgroundShellHandoffPolicy.check(2000, 432, 2000, 432,
                apk, apk, nonce, nonce, 70000, 4950, 60000), "expired nonce");
        expect(!BackgroundShellHandoffPolicy.check(2000, 432, 2000, 432,
                apk, apk, nonce, nonce, 4000, 4950, 60000), "clock regression");
        expect(!BackgroundShellHandoffPolicy.check(2000, 432, 2000, 432,
                apk, apk, "", "", 5000, 4950, 60000), "empty nonce");
        System.out.println("BackgroundShellHandoffPolicyTest OK");
    }
}
