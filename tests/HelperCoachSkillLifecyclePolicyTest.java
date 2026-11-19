package com.crewpocket.helper;

import java.util.Arrays;

public final class HelperCoachSkillLifecyclePolicyTest {
    public static void main(String[] args) {
        check(!HelperCoachSkillLifecyclePolicy.canActivate(true, 1, true),
                "one verified task cannot activate a skill");
        check(!HelperCoachSkillLifecyclePolicy.canActivate(true, 2, false),
                "regression checks are required");
        check(!HelperCoachSkillLifecyclePolicy.canActivate(false, 2, true),
                "non-candidate skill cannot activate");
        check(HelperCoachSkillLifecyclePolicy.canActivate(true, 2, true),
                "candidate activates after independent verification and regression");
        check(!HelperCoachSkillLifecyclePolicy.canRollback(false),
                "first version cannot roll back");
        check(HelperCoachSkillLifecyclePolicy.canRollback(true),
                "prior version can be restored");
        check(HelperCoachSkillLifecyclePolicy.nextVersion(2, 1) == 3,
                "version increases monotonically");
        check(HelperCoachSkillLifecyclePolicy.nextVersion(2, 5) == 5,
                "explicit higher version is preserved");
        check(HelperCoachSkillLifecyclePolicy.matchesTrustedPostcondition(
                        "com.apple.android.music", "com.apple.android.music",
                        Arrays.asList("MEDIA_PLAYBACK_BECAME_ACTIVE"),
                        "MEDIA_PLAYBACK_BECAME_ACTIVE"),
                "Apple Music candidate matches Runtime playback evidence");
        check(HelperCoachSkillLifecyclePolicy.matchesTrustedPostcondition(
                        "com.google.android.apps.maps", "com.google.android.apps.maps",
                        Arrays.asList("NAVIGATION_TRANSITION_OBSERVED"),
                        "NAVIGATION_TRANSITION_OBSERVED"),
                "Maps candidate matches Runtime navigation evidence");
        check(!HelperCoachSkillLifecyclePolicy.matchesTrustedPostcondition(
                        "com.apple.android.music", "com.google.android.apps.maps",
                        Arrays.asList("MEDIA_PLAYBACK_BECAME_ACTIVE"),
                        "MEDIA_PLAYBACK_BECAME_ACTIVE"),
                "completion from another app is not candidate verification");
        check(!HelperCoachSkillLifecyclePolicy.matchesTrustedPostcondition(
                        "com.apple.android.music", "com.apple.android.music",
                        Arrays.asList("MEDIA_PLAYBACK_BECAME_ACTIVE"), "TAP_DISPATCHED"),
                "a dispatched tap is not trusted completion evidence");
        System.out.println("HelperCoachSkillLifecyclePolicyTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
