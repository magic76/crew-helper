package com.crewpocket.helper;

public final class BackgroundDirectTransportPolicyTest {
    private static void check(boolean actual, String name) {
        if (!actual) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        check(BackgroundDirectTransportPolicy.route(true, true, true)
                == BackgroundDirectTransportPolicy.Route.DIRECT_BINDER,
                "direct always preferred");
        check(BackgroundDirectTransportPolicy.route(true, false, false)
                == BackgroundDirectTransportPolicy.Route.DIRECT_BINDER,
                "direct without Shizuku alive");
        check(BackgroundDirectTransportPolicy.route(false, true, false)
                == BackgroundDirectTransportPolicy.Route.REQUIRE_DIRECT_BINDER,
                "dead Binder never falls to EACCES socket after UI unbind");
        check(BackgroundDirectTransportPolicy.route(false, true, true)
                == BackgroundDirectTransportPolicy.Route.REQUIRE_DIRECT_BINDER,
                "pending Direct Binder bootstrap");
        check(BackgroundDirectTransportPolicy.route(false, false, true)
                == BackgroundDirectTransportPolicy.Route.REQUIRE_DIRECT_BINDER,
                "Shizuku attached but no direct Binder yet");
        check(BackgroundDirectTransportPolicy.route(false, false, false)
                == BackgroundDirectTransportPolicy.Route.LEGACY_SOCKET,
                "legacy USB ADB only when never initiated Shizuku");
        System.out.println("BackgroundDirectTransportPolicyTest OK");
    }
}
