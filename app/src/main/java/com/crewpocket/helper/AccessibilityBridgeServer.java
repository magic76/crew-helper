package com.crewpocket.helper;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** App-internal authenticated loopback bridge; never opens a public listener. */
final class AccessibilityBridgeServer {
    interface RequestHandler { void handle(Socket socket); }

    private static final String TAG = "AccessibilityBridge";
    private static final int PORT = 8766;
    private static final long RECOVERY_BACKOFF_MS = 2500L;

    private final Context appContext;
    private final RequestHandler requestHandler;
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    // A prior accept loop must never close a newer generation's socket.
    private volatile long generation;
    private volatile long lastStartMs;

    AccessibilityBridgeServer(Context context, RequestHandler requestHandler) {
        appContext = context == null ? null : context.getApplicationContext();
        this.requestHandler = requestHandler;
    }

    synchronized void start() {
        if (running) return;
        if (appContext == null || !AppConfig.isLocalBridgeEnabled(appContext)) {
            Log.i(TAG, "Local bridge disabled; not starting listener");
            return;
        }
        running = true;
        lastStartMs = SystemClock.elapsedRealtime();
        final long id = ++generation;
        acceptThread = new Thread(() -> runAcceptLoop(id),
                "crew-accessibility-bridge");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    synchronized void stop() {
        running = false;
        ++generation;
        ServerSocket old = serverSocket;
        serverSocket = null;
        Thread oldThread = acceptThread;
        acceptThread = null;
        if (old != null) {
            try { old.close(); } catch (Exception ignored) {}
        }
        if (oldThread != null) oldThread.interrupt();
    }

    boolean isListening() {
        ServerSocket socket = serverSocket;
        return running && socket != null && !socket.isClosed();
    }

    /** Only called after a bounded interval; no high-frequency event restarts. */
    void recoverIfNeeded() {
        if (running || isListening()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastStartMs < RECOVERY_BACKOFF_MS) return;
        start();
    }

    /**
     * Called before an App-internal HTTP operation. One bounded restart
     * only; no blind retry of a potentially committed phone action.
     */
    boolean ensureListening(long maxWaitMs) {
        if (appContext == null || !AppConfig.isLocalBridgeEnabled(appContext))
            return false;
        if (isListening()) return true;
        recoverIfNeeded();
        if (isListening()) return true;
        // Never wait on the Android UI thread.
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        long deadline = SystemClock.elapsedRealtime()
                + Math.max(0L, Math.min(750L, maxWaitMs));
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isListening()) return true;
            try { Thread.sleep(25L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isListening();
    }

    private void runAcceptLoop(long id) {
        ServerSocket owned = null;
        try {
            owned = new ServerSocket(
                    PORT, 50, InetAddress.getByName("127.0.0.1"));
            synchronized (this) {
                if (!running || generation != id) return;
                serverSocket = owned;
            }
            while (running && generation == id && !owned.isClosed()) {
                final Socket client = owned.accept();
                if (!running || generation != id) {
                    try { client.close(); } catch (Exception ignored) {}
                    break;
                }
                Thread handler = new Thread(() -> {
                    if (requestHandler == null) {
                        try { client.close(); } catch (Exception ignored) {}
                    } else {
                        requestHandler.handle(client);
                    }
                }, "crew-accessibility-bridge-request");
                handler.setDaemon(true);
                handler.start();
            }
        } catch (Exception error) {
            if (running && generation == id)
                Log.e(TAG, "Local bridge bind/accept failed", error);
        } finally {
            synchronized (this) {
                if (generation == id) {
                    running = false;
                    serverSocket = null;
                    acceptThread = null;
                }
            }
            if (owned != null) {
                try { owned.close(); } catch (Exception ignored) {}
            }
        }
    }
}
