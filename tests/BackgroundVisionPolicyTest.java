package com.crewpocket.helper;

public final class BackgroundVisionPolicyTest {
    private static void check(boolean b, String msg) {
        if (!b) throw new AssertionError(msg);
    }

    public static void main(String[] args) {
        check(BackgroundVisionPolicy.permittedPackage("com.apple.android.music"), "Apple Music");
        check(BackgroundVisionPolicy.permittedPackage("com.google.android.apps.maps"), "Maps");
        check(!BackgroundVisionPolicy.permittedPackage("com.whatsapp"), "unknown App blocked");
        check(BackgroundVisionPolicy.explicitVisualRequest("看看背景畫面"), "explicit image");
        check(BackgroundVisionPolicy.lowRiskTarget("點播放"), "play");
        check(BackgroundVisionPolicy.lowRiskTarget("搜尋按鈕"), "search");
        check(!BackgroundVisionPolicy.lowRiskTarget("確認支付"), "payment");
        check(!BackgroundVisionPolicy.lowRiskTarget("傳送訊息"), "send");
        check(!BackgroundVisionPolicy.lowRiskTarget("login account"), "account");
        check(!BackgroundVisionPolicy.lowRiskTarget("something"), "unknown");
        check(BackgroundVisionPolicy.coordinate(0, 720) == 0, "start x");
        check(BackgroundVisionPolicy.coordinate(1000, 720) == 719, "end x");
        check(BackgroundVisionPolicy.coordinate(500, 721) == 360, "middle");
        check(BackgroundVisionPolicy.validCoordinate(0d), "lower");
        check(BackgroundVisionPolicy.validCoordinate(1000d), "upper");
        check(!BackgroundVisionPolicy.validCoordinate(Double.NaN), "nan");
        check(!BackgroundVisionPolicy.validCoordinate(-1d), "negative");
        check(!BackgroundVisionPolicy.validCoordinate(1001d), "out of range");
        int[] same = new int[64];
        int[] mild = new int[64];
        int[] different = new int[64];
        java.util.Arrays.fill(same, 0xff222222);
        java.util.Arrays.fill(mild, 0xff272727);
        java.util.Arrays.fill(different, 0xffffffff);
        check(BackgroundVisionPolicy.similarFrames(same, same), "identical frame");
        check(BackgroundVisionPolicy.similarFrames(same, mild), "mild compression");
        check(!BackgroundVisionPolicy.similarFrames(same, different), "different frame");
        check(!BackgroundVisionPolicy.similarFrames(null, mild), "no frame");
        check(!BackgroundVisionPolicy.similarFrames(new int[32], mild), "bad dimensions");
        check(BackgroundVisionPolicy.fresh(500, 400, 150), "fresh frame");
        check(!BackgroundVisionPolicy.fresh(600, 400, 150), "expired frame");
        check(!BackgroundVisionPolicy.fresh(300, 400, 150), "future frame");
        System.out.println("BackgroundVisionPolicyTest OK");
    }
}
