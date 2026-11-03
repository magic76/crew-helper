package com.crewpocket.helper;

/** Pure routing policy: no Shizuku-started session may fall back to a Shell socket. */
final class BackgroundDirectTransportPolicy {
    enum Route { DIRECT_BINDER, REQUIRE_DIRECT_BINDER, LEGACY_SOCKET }

    private BackgroundDirectTransportPolicy() {}

    static Route route(boolean directAlive, boolean directRequired,
                       boolean shizukuBootstrapAlive) {
        if (directAlive) return Route.DIRECT_BINDER;
        if (directRequired || shizukuBootstrapAlive)
            return Route.REQUIRE_DIRECT_BINDER;
        return Route.LEGACY_SOCKET;
    }
}
