package com.crewpocket.helper;

public final class SecretMigrationPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check("secure".equals(
                        SecretMigrationPolicy.readableValue(
                                "secure",
                                "legacy")),
                "secure value wins over plaintext legacy");
        check("legacy".equals(
                        SecretMigrationPolicy.readableValue(
                                "",
                                "legacy")),
                "legacy remains readable before migration");
        check(SecretMigrationPolicy.mayDeleteLegacy(
                        "same",
                        "same",
                        true),
                "verified secure write allows plaintext cleanup");
        check(!SecretMigrationPolicy.mayDeleteLegacy(
                        "same",
                        "",
                        true),
                "empty secure read never deletes plaintext");
        check(!SecretMigrationPolicy.mayDeleteLegacy(
                        "same",
                        "different",
                        true),
                "mismatched secure read never deletes plaintext");
        check(!SecretMigrationPolicy.mayDeleteLegacy(
                        "same",
                        "same",
                        false),
                "failed secure write never deletes plaintext");

        System.out.println(
                "SecretMigrationPolicyTest passed "
                        + checks + " checks");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
