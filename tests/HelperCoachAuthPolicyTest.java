package com.crewpocket.helper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class HelperCoachAuthPolicyTest {
    private static final class Nonces implements HelperCoachAuthPolicy.NonceStore {
        private final Set<String> values = new HashSet<String>();
        @Override public synchronized boolean markIfNew(
                String nonce, long timestampMs, long nowMs) {
            return values.add(nonce);
        }
    }

    public static void main(String[] args) throws Exception {
        String secret = repeat("a1", 32);
        byte[] body = "{\"query\":\"蔡依林\",\"kind\":\"task_observation\"}"
                .getBytes(StandardCharsets.UTF_8);
        long now = 1_800_000_000_000L;
        String timestamp = Long.toString(now);
        String nonce = "9d98bcb6-477f-4223-9f09-27e2262b00db";
        String signature = HelperCoachAuthPolicy.sign(
                secret, "POST", "/coach/v1/advice", timestamp, nonce, body);
        Nonces nonces = new Nonces();

        check(verify(true, secret, timestamp, nonce, signature, body, now, nonces)
                == HelperCoachAuthPolicy.Result.VALID, "valid request");
        check(verify(true, secret, timestamp, nonce, signature, body, now, nonces)
                == HelperCoachAuthPolicy.Result.REPLAYED_NONCE, "replay rejected");
        check(verify(true, secret, Long.toString(now - 61_000L),
                "bd98bcb6-477f-4223-9f09-27e2262b00db", signature, body,
                now, new Nonces()) == HelperCoachAuthPolicy.Result.STALE_TIMESTAMP,
                "stale timestamp rejected");
        check(verify(true, secret, timestamp,
                "dd98bcb6-477f-4223-9f09-27e2262b00db", "00", body,
                now, new Nonces()) == HelperCoachAuthPolicy.Result.BAD_SIGNATURE,
                "bad signature rejected");
        check(verify(false, secret, timestamp,
                "ad98bcb6-477f-4223-9f09-27e2262b00db", signature, body,
                now, new Nonces()) == HelperCoachAuthPolicy.Result.DISABLED,
                "opt-out disables route");
        check(verify(true, secret, timestamp,
                "cd98bcb6-477f-4223-9f09-27e2262b00db", signature,
                new byte[HelperCoachAuthPolicy.MAX_BODY_BYTES + 1],
                now, new Nonces()) == HelperCoachAuthPolicy.Result.BODY_TOO_LARGE,
                "oversized body rejected");
        System.out.println("HelperCoachAuthPolicyTest passed");
    }

    private static HelperCoachAuthPolicy.Result verify(
            boolean enabled, String secret, String timestamp, String nonce,
            String signature, byte[] body, long now,
            HelperCoachAuthPolicy.NonceStore nonces) {
        return HelperCoachAuthPolicy.verify(
                enabled, secret, "POST", "/coach/v1/advice", timestamp,
                nonce, signature, body, now, nonces);
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
