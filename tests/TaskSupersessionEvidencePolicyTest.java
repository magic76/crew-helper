package com.crewpocket.helper;

public final class TaskSupersessionEvidencePolicyTest {
    private static int checks;
    private static void check(boolean ok, String detail) {
        checks++;
        if (!ok) throw new AssertionError(detail);
    }
    public static void main(String[] args) {
        check(TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "APP:OPEN","DONE","APP_OPEN_FOREGROUND_VERIFIED",
                true,false,false), "foreground-verified app open may close");
        check(!TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "APP:OPEN","EVIDENCE_AVAILABLE","RUNTIME_V2_VERIFIED",
                true,false,false), "mere app transition is insufficient");
        check(TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "NAVIGATION:START","DONE","MAPS_NAVIGATION_ACTIVE_VERIFIED",
                true,false,false), "active navigation closes");
        check(!TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "NAVIGATION:START","DONE","RUNTIME_V2_VERIFIED",
                true,false,false), "navigation tap alone is not navigation");
        check(TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "MEDIA:PLAY","DONE","MEDIA_PLAYBACK_BECAME_ACTIVE",
                true,false,false), "actual playback starts");
        check(TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "MEDIA:PLAY","DONE","MEDIA_UI_PLAYING",
                true,false,false), "playing media UI verified");
        check(!TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "MEDIA:PLAY","DONE","MEDIA_PLAY_CONTROL_VERIFIED",
                true,false,false), "tap effect alone is not playback");
        check(!TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "MEDIA:PLAY","DONE","MEDIA_UI_PLAYING",
                false,false,false), "unverified claimed playback is rejected");
        check(!TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "MEDIA:PLAY","DONE","MEDIA_UI_PLAYING",
                true,true,false), "inspection still required");
        check(!TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "MEDIA:PLAY","DONE","MEDIA_UI_PLAYING",
                true,false,true), "Runtime block outranks model");
        check(TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "SEARCH:RESULT","ANSWER_READY","SEARCH_RESULT_SCREEN_INSPECTED",
                true,false,false), "search result complete if search is goal");
        check(!TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "MEDIA:PLAY","ANSWER_READY","SEARCH_RESULT_SCREEN_INSPECTED",
                true,false,false), "search results not equal playback goal");
        check(!TaskSupersessionEvidencePolicy.canFinalizePriorGoal(
                "MESSAGE:SEND","DONE","RUNTIME_V2_VERIFIED",
                true,false,false), "messaging cannot be auto finalized");
        check(TaskSupersessionEvidencePolicy.VERIFIED_STEP_ONLY.equals(
                TaskSupersessionEvidencePolicy.progressAtCancellation(
                    "MEDIA:PLAY","EVIDENCE_AVAILABLE","RUNTIME_V2_VERIFIED",
                    true,false,false,1)), "verified tap but unfinished goal");
        check(TaskSupersessionEvidencePolicy.EXECUTED_UNVERIFIED.equals(
                TaskSupersessionEvidencePolicy.progressAtCancellation(
                    "MEDIA:PLAY","IN_PROGRESS","UNKNOWN",
                    false,true,false,1)), "unverified mutation not success");
        check(TaskSupersessionEvidencePolicy.NO_MUTATION.equals(
                TaskSupersessionEvidencePolicy.progressAtCancellation(
                    "MEDIA:PLAY","IN_PROGRESS","",
                    false,false,false,0)), "zero actions counted separately");
        check(TaskSupersessionEvidencePolicy.VERIFIED_GOAL.equals(
                TaskSupersessionEvidencePolicy.progressAtCancellation(
                    "APP:OPEN","DONE","APP_OPEN_FOREGROUND_VERIFIED",
                    true,false,false,1)), "goal completion distinguishable");
        System.out.println("PASS TaskSupersessionEvidencePolicyTest: " + checks + " checks");
    }
}
