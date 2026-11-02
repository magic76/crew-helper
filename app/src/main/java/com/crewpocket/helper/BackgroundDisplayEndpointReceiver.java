package com.crewpocket.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Receives a per-helper endpoint; its token stays in the app's private no-backup directory. */
public final class BackgroundDisplayEndpointReceiver extends BroadcastReceiver {
    static final String ACTION = "com.crewpocket.helper.BG_ENDPOINT_READY";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_TOKEN = "token";
    private static final String ENDPOINT_FILE = "background_display_endpoint_v1";
    private static final int MAX_ENDPOINT_BYTES = 128;
    private static final Object LOCK = new Object();

    private static volatile Endpoint endpoint;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (port < 1 || port > 65535 || token == null || token.length() != 43) return;
        Endpoint announced = new Endpoint(port, token);
        synchronized (LOCK) {
            persist(context.getApplicationContext(), announced);
            endpoint = announced;
        }
    }

    static Endpoint getEndpoint(Context context) {
        Endpoint current = endpoint;
        if (current != null || context == null) return current;
        synchronized (LOCK) {
            if (endpoint != null) return endpoint;
            AtomicFile file = endpointFile(context.getApplicationContext());
            try (FileInputStream input = file.openRead()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[64];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_ENDPOINT_BYTES) return null;
                    bytes.write(buffer, 0, count);
                }
                String value = new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
                int separator = value.indexOf('\n');
                if (separator <= 0) return null;
                int port = Integer.parseInt(value.substring(0, separator));
                String token = value.substring(separator + 1);
                if (port < 1 || port > 65535 || token.length() != 43) return null;
                endpoint = new Endpoint(port, token);
            } catch (Exception ignored) {
                return null;
            }
            return endpoint;
        }
    }

    private static AtomicFile endpointFile(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), ENDPOINT_FILE));
    }

    private static void persist(Context context, Endpoint value) {
        AtomicFile file = endpointFile(context);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            String data = value.port + "\n" + value.token;
            output.write(data.getBytes(StandardCharsets.US_ASCII));
            file.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
        }
    }

    static final class Endpoint {
        final int port;
        final String token;

        Endpoint(int port, String token) {
            this.port = port;
            this.token = token;
        }
    }
}
