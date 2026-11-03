package com.crewpocket.helper;

public final class BackgroundShizukuCommandPolicyTest {
    private static void yes(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        for (String op : new String[]{"status", "open", "preview", "tap",
                "swipe", "front", "close"}) {
            yes(BackgroundShizukuCommandPolicy.allowedOperation(op), "allow " + op);
        }
        for (String op : new String[]{"exec", "su", "shell", "kill", "chmod",
                "download", "rm", "read_file", "", "status;rm"}) {
            yes(!BackgroundShizukuCommandPolicy.allowedOperation(op), "deny " + op);
        }
        yes(BackgroundShizukuCommandPolicy.completeDisplayIdentity(false, false),
                "manual UI request can bind both fields");
        yes(BackgroundShizukuCommandPolicy.completeDisplayIdentity(true, true),
                "Live request with complete identity");
        yes(!BackgroundShizukuCommandPolicy.completeDisplayIdentity(true, false),
                "stale display-only requests denied");
        yes(!BackgroundShizukuCommandPolicy.completeDisplayIdentity(false, true),
                "stale package-only requests denied");
        yes(BackgroundShizukuCommandPolicy.requiresDisplay("tap"), "tap bound");
        yes(BackgroundShizukuCommandPolicy.requiresDisplay("preview"), "preview bound");
        yes(!BackgroundShizukuCommandPolicy.requiresDisplay("status"), "status");
        yes(!BackgroundShizukuCommandPolicy.requiresDisplay("open"), "open");
        yes(BackgroundShizukuCommandPolicy.validPackage("com.apple.android.music"), "pkg");
        yes(!BackgroundShizukuCommandPolicy.validPackage("com.foo;rm -rf /"), "shell injection");
        yes(!BackgroundShizukuCommandPolicy.validPackage("single"), "one component");
        yes(!BackgroundShizukuCommandPolicy.validPackage(""), "empty");
        yes(BackgroundShizukuCommandPolicy.validCoordinate(0), "coord lower");
        yes(BackgroundShizukuCommandPolicy.validCoordinate(8192), "coord upper");
        yes(!BackgroundShizukuCommandPolicy.validCoordinate(-1), "coord negative");
        yes(!BackgroundShizukuCommandPolicy.validCoordinate(8193), "coord excessive");
        yes(BackgroundShizukuCommandPolicy.authorizedCaller(2000, 10342, 10342),
                "shell plus app identity");
        yes(!BackgroundShizukuCommandPolicy.authorizedCaller(2000, 10342, 10343),
                "other app");
        yes(!BackgroundShizukuCommandPolicy.authorizedCaller(0, 10342, 10342),
                "root service unsupported");
        yes(!BackgroundShizukuCommandPolicy.authorizedCaller(2000, -1, -1),
                "unresolved package uid");
        System.out.println("BackgroundShizukuCommandPolicyTest OK");
    }
}
