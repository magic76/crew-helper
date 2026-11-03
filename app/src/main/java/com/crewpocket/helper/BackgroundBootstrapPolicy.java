package com.crewpocket.helper;

/** Only a running Shell Binder, not Shizuku availability, proves an offline path. */
final class BackgroundBootstrapPolicy {
    enum State {
        DIRECT_BINDER_ALIVE,
        SHIZUKU_BOOTSTRAP_READY,
        SHIZUKU_PERMISSION_REQUIRED,
        DIRECT_BINDER_LOST_BOOTSTRAP_NEEDED,
        BOOTSTRAP_NEEDED
    }

    private BackgroundBootstrapPolicy() {}

    static State classify(boolean directAlive, boolean hadDirect,
                          boolean shizukuAlive, boolean shizukuAuthorized) {
        if (directAlive) return State.DIRECT_BINDER_ALIVE;
        if (shizukuAlive && shizukuAuthorized) return State.SHIZUKU_BOOTSTRAP_READY;
        if (shizukuAlive) return State.SHIZUKU_PERMISSION_REQUIRED;
        return hadDirect ? State.DIRECT_BINDER_LOST_BOOTSTRAP_NEEDED
                         : State.BOOTSTRAP_NEEDED;
    }
}
