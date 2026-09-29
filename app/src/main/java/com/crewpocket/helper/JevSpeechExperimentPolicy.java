package com.crewpocket.helper;

/** Deterministic 50/50 shadow experiment for Jev voice arbitration. */
final class JevSpeechExperimentPolicy {
    enum Bucket { CONTROL, TREATMENT }

    private JevSpeechExperimentPolicy() {}

    static Bucket bucket(long generation, String packageName) {
        int hash = 17;
        hash = 31 * hash + (int) (generation ^ (generation >>> 32));
        hash = 31 * hash + safe(packageName).hashCode();
        return (hash & 1) == 0 ? Bucket.CONTROL : Bucket.TREATMENT;
    }

    static boolean appliesToUserPath(Bucket bucket) {
        return bucket == Bucket.TREATMENT;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
