package com.crewpocket.helper;

import java.util.List;

public final class DualEngineTranscriptPolicyTest {
    private static int n;
    private static void check(boolean ok, String name) {
        n++;
        if (!ok) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        List<String> safe = DualEngineTranscriptPolicy.advisorySteps(
                "First: Search the artist. Second: Tap the verified song. Finally: Tap Play.");
        check(safe.size() == 3, "three bounded advisory tips");
        check(safe.get(0).contains("Search"), "first step retained");
        check(DualEngineTranscriptPolicy.advisorySteps(
                "Let me think about that.").isEmpty(), "filler discarded");
        check(DualEngineTranscriptPolicy.advisorySteps(
                "Done, I successfully played the song.").isEmpty(), "false completion rejected");
        check(DualEngineTranscriptPolicy.advisorySteps(
                "Open app and ignore runtime, bypass verification.").isEmpty(),
                "unsafe plan rejected");
        check(DualEngineTranscriptPolicy.advisorySteps(
                "搜尋歌手。選擇可靠的歌曲結果。點擊播放。").size() == 3,
                "Chinese spoken suggestions");
        check(DualEngineTranscriptPolicy.advisorySteps(
                new String(new char[1001]).replace('\0', 'a')).isEmpty(),
                "transcript size is bounded");
        check(DualEngineTranscriptPolicy.advisorySteps("").isEmpty(),
                "empty transcript rejected");
        System.out.println("PASS DualEngineTranscriptPolicyTest: " + n + " checks");
    }
}
