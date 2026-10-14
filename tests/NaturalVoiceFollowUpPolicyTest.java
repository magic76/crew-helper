package com.crewpocket.helper;

public final class NaturalVoiceFollowUpPolicyTest {
    private static int checks;
    private static void check(boolean actual, String message) {
        checks++;
        if (!actual) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (String command : new String[]{
                "再往右滑一下", "幫我往右邊一點", "再一下",
                "再來一次", "第二個", "請選第三項", "下面那個",
                "下一首", "點播放"}) {
            check(NaturalVoiceFollowUpPolicy.isIndependentAction(command),
                    "recognize short instruction: " + command);
        }
        for (String command : new String[]{
                "然後導航過去", "然後點第二個", "播放周杰倫的歌",
                "再輸入密碼", "直接刪除全部", "傳訊息給小明", "右邊的是什麼"}) {
            check(!NaturalVoiceFollowUpPolicy.isIndependentAction(command),
                    "do not infer an unrelated/sensitive action: " + command);
        }
        System.out.println("PASS NaturalVoiceFollowUpPolicyTest: " + checks + " checks");
    }
}
