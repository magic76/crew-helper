package com.crewpocket.helper;

import android.content.Context;
import android.util.Log;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Owns the loopback HTTP bridge transport used by the accessibility Runtime.
 *
 * Request semantics remain in CrewAccessibilityService for now. This class owns
 * only socket lifecycle, bind/accept, and per-connection dispatch so the
 * AccessibilityService no longer mixes Android lifecycle with server transport.
 */
final class AccessibilityBridgeServer {
    interface RequestHandler {
        void handle(Socket socket);
    }

    private static final String TAG = "AccessibilityBridge";
    private static final int PORT = 8766;

    private final Context appContext;
    private final RequestHandler requestHandler;

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;

    AccessibilityBridgeServer(Context context, RequestHandler requestHandler) {
        this.appContext = context == null ? null : context.getApplicationContext();
        this.requestHandler = requestHandler;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() {
                runAcceptLoop();
            }
        }, "crew-accessibility-bridge");
        acceptThread.start();
    }

    synchronized void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null) thread.interrupt();
    }

    private void runAcceptLoop() {
        try {
            if (appContext == null || !AppConfig.isLocalBridgeEnabled(appContext)) {
                Log.i(TAG, "Local bridge disabled; server not started");
                running = false;
                return;
            }

            ServerSocket socket = new ServerSocket(
                    PORT,
                    50,
                    InetAddress.getByName("127.0.0.1"));
            serverSocket = socket;

            while (running && !socket.isClosed()) {
                final Socket client = socket.accept();
                new Thread(new Runnable() {
                    @Override public void run() {
                        if (requestHandler == null) {
                            try { client.close(); } catch (Exception ignored) {}
                            return;
                        }
                        requestHandler.handle(client);
                    }
                }, "crew-accessibility-bridge-request").start();
            }
        } catch (Exception error) {
            if (running) {
                Log.e(TAG, "Local bridge stopped unexpectedly", error);
            }
        } finally {
            running = false;
            ServerSocket socket = serverSocket;
            serverSocket = null;
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}
