package com.crewpocket.helper;

/** Optional native Oboe playback. Any load/start failure keeps Java AudioTrack active. */
final class NativeOboeOutput {
    private static boolean available;
    static {
        try { System.loadLibrary("crewaudio"); available = true; } catch (Throwable ignored) { available = false; }
    }
    static boolean start() {
        try { return available && nativeStart(); }
        catch (Throwable ignored) { available = false; return false; }
    }
    static void stop() { if (available) nativeStop(); }
    static void flush() { if (available) nativeFlush(); }
    static void write(byte[] pcm) { if (available && pcm != null && pcm.length > 0) nativeWrite(pcm, pcm.length); }
    static String getInfo() {
        try { return available ? nativeGetInfo() : null; }
        catch (Throwable ignored) { return null; }
    }
    private static native boolean nativeStart();
    private static native void nativeStop();
    private static native void nativeFlush();
    private static native void nativeWrite(byte[] pcm, int length);
    private static native String nativeGetInfo();
}
