package com.crewpocket.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives a per-helper session endpoint from adb shell. Never persists or logs the token. */
public final class BackgroundDisplayEndpointReceiver extends BroadcastReceiver {
    static final String ACTION = "com.crewpocket.helper.BG_ENDPOINT_READY";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_TOKEN = "token";

    private static volatile Endpoint endpoint;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (port < 1 || port > 65535 || token == null || token.length() != 43) return;
        endpoint = new Endpoint(port, token);
    }

    static Endpoint getEndpoint() { return endpoint; }

    static final class Endpoint {
        final int port;
        final String token;

        Endpoint(int port, String token) {
            this.port = port;
            this.token = token;
        }
    }
}
