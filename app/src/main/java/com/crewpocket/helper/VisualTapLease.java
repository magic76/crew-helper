package com.crewpocket.helper;

/**
 * One-shot authority for a visual-coordinate TAP derived from the latest
 * inspect_ui screenshot.
 *
 * The lease binds execution to one recent package + screen fingerprint. It does
 * not grant any extra action authority; callers must still enforce normal
 * safety policy against the semantic target.
 */
final class VisualTapLease {
    static final long TTL_MS = 10_000L;

    static final class Snapshot {
        final String id;
        final String packageName;
        final String fingerprint;
        final long issuedAtMs;

        Snapshot(
                String id,
                String packageName,
                String fingerprint,
                long issuedAtMs) {
            this.id = clean(id);
            this.packageName = clean(packageName);
            this.fingerprint = clean(fingerprint);
            this.issuedAtMs = issuedAtMs;
        }

        boolean available() {
            return !id.isEmpty()
                    && !packageName.isEmpty()
                    && !fingerprint.isEmpty();
        }
    }

    static final class Validation {
        final boolean allowed;
        final String code;
        final String message;

        Validation(boolean allowed, String code, String message) {
            this.allowed = allowed;
            this.code = clean(code);
            this.message = clean(message);
        }

        static Validation allow() {
            return new Validation(true, "ALLOW", "");
        }

        static Validation reject(String code, String message) {
            return new Validation(false, code, message);
        }
    }

    private Snapshot active;
    private boolean consumed;
    private long sequence;

    synchronized Snapshot arm(
            String packageName,
            String fingerprint,
            long nowMs) {
        String pkg = clean(packageName);
        String fp = clean(fingerprint);
        if (pkg.isEmpty() || fp.isEmpty()) {
            clear();
            return new Snapshot("", "", "", 0L);
        }

        sequence++;
        String id = "vt_" + Long.toHexString(nowMs)
                + "_" + Long.toHexString(sequence);
        active = new Snapshot(id, pkg, fp, nowMs);
        consumed = false;
        return active;
    }

    synchronized Snapshot current() {
        return active == null
                ? new Snapshot("", "", "", 0L)
                : active;
    }

    synchronized Validation validateAndConsume(
            String leaseId,
            String currentPackage,
            String currentFingerprint,
            double x,
            double y,
            long nowMs) {
        if (active == null || !active.available()) {
            return Validation.reject(
                    "VISUAL_TAP_LEASE_MISSING",
                    "沒有可用的 fresh inspect_ui visual tap lease。");
        }
        if (consumed) {
            return Validation.reject(
                    "VISUAL_TAP_LEASE_CONSUMED",
                    "visual tap lease 已使用；請重新 inspect_ui。");
        }
        if (!active.id.equals(clean(leaseId))) {
            return Validation.reject(
                    "VISUAL_TAP_LEASE_MISMATCH",
                    "visual tap lease 與最新 inspect_ui 不一致。");
        }
        long age = nowMs - active.issuedAtMs;
        if (age < 0L || age > TTL_MS) {
            clear();
            return Validation.reject(
                    "VISUAL_TAP_LEASE_EXPIRED",
                    "visual tap lease 已過期；請重新 inspect_ui。");
        }
        if (!active.packageName.equals(clean(currentPackage))) {
            clear();
            return Validation.reject(
                    "VISUAL_TAP_PACKAGE_CHANGED",
                    "前景 App 已改變；舊截圖不能再用來點擊。");
        }
        if (!active.fingerprint.equals(clean(currentFingerprint))) {
            clear();
            return Validation.reject(
                    "VISUAL_TAP_SCREEN_CHANGED",
                    "畫面已改變；舊截圖座標已失效。");
        }
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || x < 0.0 || x > 1000.0
                || y < 0.0 || y > 1000.0) {
            return Validation.reject(
                    "VISUAL_TAP_COORDINATE_INVALID",
                    "visual_x / visual_y 必須是 0..1000 的 normalized coordinates。");
        }

        consumed = true;
        return Validation.allow();
    }

    synchronized void clear() {
        active = null;
        consumed = false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
