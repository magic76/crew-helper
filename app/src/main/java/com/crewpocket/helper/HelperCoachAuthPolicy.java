package com.crewpocket.helper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Pure request authentication policy for the paired Helper Coach bridge. */
final class HelperCoachAuthPolicy {
    static final int MAX_BODY_BYTES = 32 * 1024;
    static final long MAX_CLOCK_SKEW_MS = 60_000L;

    interface NonceStore {
        /** Atomically records a nonce; false means that it was already used. */
        boolean markIfNew(String nonce, long timestampMs, long nowMs);
    }

    enum Result {
        VALID,
        DISABLED,
        MISSING_SECRET,
        BODY_TOO_LARGE,
        BAD_TIMESTAMP,
        STALE_TIMESTAMP,
        BAD_NONCE,
        BAD_SIGNATURE,
        REPLAYED_NONCE,
        AUTH_ERROR
    }

    private HelperCoachAuthPolicy() {}

    static Result verify(
            boolean enabled,
            String secretHex,
            String method,
            String path,
            String timestamp,
            String nonce,
            String signature,
            byte[] body,
            long nowMs,
            NonceStore nonces) {
        if (!enabled) return Result.DISABLED;
        if (secretHex == null || secretHex.length() < 64) {
            return Result.MISSING_SECRET;
        }
        byte[] safeBody = body == null ? new byte[0] : body;
        if (safeBody.length > MAX_BODY_BYTES) return Result.BODY_TOO_LARGE;

        long timestampMs;
        try {
            timestampMs = Long.parseLong(timestamp == null ? "" : timestamp.trim());
        } catch (Exception invalid) {
            return Result.BAD_TIMESTAMP;
        }
        if (!HelperCoachTime.withinClockSkew(
                timestampMs, nowMs, MAX_CLOCK_SKEW_MS)) {
            return Result.STALE_TIMESTAMP;
        }

        String cleanNonce = nonce == null ? "" : nonce.trim();
        if (!cleanNonce.matches("[A-Za-z0-9_-]{16,128}")) {
            return Result.BAD_NONCE;
        }

        try {
            String expected = sign(
                    secretHex,
                    method,
                    path,
                    timestamp.trim(),
                    cleanNonce,
                    safeBody);
            if (!constantTimeEquals(expected, signature)) {
                return Result.BAD_SIGNATURE;
            }
            if (nonces != null
                    && !nonces.markIfNew(cleanNonce, timestampMs, nowMs)) {
                return Result.REPLAYED_NONCE;
            }
            return Result.VALID;
        } catch (Exception error) {
            return Result.AUTH_ERROR;
        }
    }

    static String sign(
            String secretHex,
            String method,
            String path,
            String timestamp,
            String nonce,
            byte[] body) throws Exception {
        byte[] key = fromHex(secretHex);
        String bodyHash = hex(MessageDigest.getInstance("SHA-256")
                .digest(body == null ? new byte[0] : body));
        String canonical = cleanUpper(method) + "\n"
                + clean(path) + "\n"
                + clean(timestamp) + "\n"
                + clean(nonce) + "\n"
                + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        byte[] left = expected.toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] right = actual.trim().toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);
        int diff = left.length ^ right.length;
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            diff |= a ^ b;
        }
        return diff == 0;
    }

    private static byte[] fromHex(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() < 64 || clean.length() % 2 != 0) {
            throw new IllegalArgumentException("invalid coach secret");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            int high = Character.digit(clean.charAt(i), 16);
            int low = Character.digit(clean.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid coach secret");
            }
            out[i / 2] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte item : value) out.append(String.format(Locale.US, "%02x", item & 0xff));
        return out.toString();
    }

    private static String cleanUpper(String value) {
        return clean(value).toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
