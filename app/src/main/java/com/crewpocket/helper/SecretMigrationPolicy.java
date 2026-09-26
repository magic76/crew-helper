package com.crewpocket.helper;

/** Pure migration rules for moving a plaintext secret into secure storage. */
final class SecretMigrationPolicy {
    private SecretMigrationPolicy() {}

    static boolean mayDeleteLegacy(
            String legacyValue,
            String secureValue,
            boolean secureWriteSucceeded) {
        String legacy = clean(legacyValue);
        String secure = clean(secureValue);
        return secureWriteSucceeded
                && !legacy.isEmpty()
                && legacy.equals(secure);
    }

    static String readableValue(
            String secureValue,
            String legacyValue) {
        String secure = clean(secureValue);
        if (!secure.isEmpty()) return secure;
        return clean(legacyValue);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
