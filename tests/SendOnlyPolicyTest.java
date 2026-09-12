package com.crewpocket.helper;

public final class SendOnlyPolicyTest {
    private static int assertions;

    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("送出"), "send out");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("發送"), "send zh");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("幫我按一下送出"), "press send");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("幫我點送出按鈕"), "tap send button");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("send"), "send en");
        check(UserActionScope.isStandaloneCurrentScreenSendCommand("please send this"), "natural english send");

        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("不要送出"), "negated send");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("先不要送出"), "deferred send");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("輸入送出"), "typed word send");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("輸入晚點到並送出"), "new text plus send");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("傳給小明"), "recipient routing");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("怎麼送出"), "how-to question");
        check(!UserActionScope.isStandaloneCurrentScreenSendCommand("如果要送出怎麼辦"), "hypothetical");

        System.out.println("PASS SendOnlyPolicyTest: " + assertions + " checks");
    }
}
