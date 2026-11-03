package com.crewpocket.helper;

import rikka.shizuku.Shizuku;

/**
 * Bootstrap-only Shizuku UserService reference. Background actions are
 * intentionally NOT exposed on this Binder; they go directly to the
 * verified BackgroundShellHandoff binder after startup.
 */
final class BackgroundShizukuRelay {
    private static volatile IBackgroundShizukuService remote;

    private BackgroundShizukuRelay() {}

    static boolean attach(IBackgroundShizukuService service) {
        try {
            if (service == null || !service.asBinder().isBinderAlive()
                    || service.shellUid() != 2000) return false;
            remote = service;
            return true;
        } catch (Exception ignored) {
            remote = null;
            return false;
        }
    }

    static boolean connected() {
        IBackgroundShizukuService service = remote;
        return service != null && service.asBinder().isBinderAlive();
    }

    static void detach() { remote = null; }
}
