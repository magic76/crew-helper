package com.crewpocket.helper;

/** A fresh snapshot alone does not resolve an explicitly pending action. */
final class PostActionEvidence {
    private PostActionEvidence() {}

    static boolean usable(boolean fresh, String source, boolean verificationPending) {
        return fresh && "AUTO_AFTER_ACTION".equals(source) && !verificationPending;
    }
}
