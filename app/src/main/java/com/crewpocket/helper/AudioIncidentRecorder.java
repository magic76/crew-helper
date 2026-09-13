package com.crewpocket.helper;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 0029 debug-only rolling audio black box.
 *
 * Keeps only the last ~5 seconds of PCM in RAM. On the first mutation attempt of
 * each Agent task, DEBUG builds persist the pre-mutation audio plus redacted
 * metadata. Release builds never retain or write audio.
 */
final class AudioIncidentRecorder {
    private static final String TAG = "CrewAudioIncident";
    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int MAX_RING_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * 5;
    private static final int MAX_INCIDENTS = 5;
    private static final int MAX_TRANSCRIPT_CHARS = 600;

    private final Context context;
    // Fixed rolling PCM ring: no new byte[] for every 40 ms microphone frame.
    private final byte[] ring = new byte[MAX_RING_BYTES];
    private int ringWritePosition;
    private int ringBytes;

    private String latestTranscript = "";
    private long latestTranscriptAt;
    private String latestInputKind = "none";

    private double lastRms;
    private double lastNoiseFloor;
    private double lastGate;
    private double decayedPeakRms;

    private String lastCapturedTaskId = "";

    AudioIncidentRecorder(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    /** build.sh does not generate Gradle's BuildConfig; use the manifest flag. */
    private boolean isDebugBuild() {
        return context != null && (context.getApplicationInfo().flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    synchronized void onUpstreamPcm(
            byte[] pcm,
            int count,
            double rms,
            double noiseFloor,
            double gate) {
        if (!isDebugBuild() || pcm == null || pcm.length == 0 || count <= 0) return;
        int safeCount = Math.min(count, pcm.length);
        if (safeCount >= MAX_RING_BYTES) {
            System.arraycopy(pcm, safeCount - MAX_RING_BYTES, ring, 0, MAX_RING_BYTES);
            ringWritePosition = 0;
            ringBytes = MAX_RING_BYTES;
        } else {
            int first = Math.min(safeCount, MAX_RING_BYTES - ringWritePosition);
            System.arraycopy(pcm, 0, ring, ringWritePosition, first);
            int remaining = safeCount - first;
            if (remaining > 0) System.arraycopy(pcm, first, ring, 0, remaining);
            ringWritePosition = (ringWritePosition + safeCount) % MAX_RING_BYTES;
            ringBytes = Math.min(MAX_RING_BYTES, ringBytes + safeCount);
        }
        lastRms = rms;
        lastNoiseFloor = noiseFloor;
        lastGate = gate;
        decayedPeakRms = Math.max(rms, decayedPeakRms * 0.992);
    }

    synchronized void onVoiceTranscript(String text) {
        if (!isDebugBuild()) return;
        latestTranscript = clip(text, MAX_TRANSCRIPT_CHARS);
        latestTranscriptAt = System.currentTimeMillis();
        latestInputKind = "voice";
    }

    synchronized void markTypedInput(String text) {
        if (!isDebugBuild()) return;
        latestTranscript = "<typed input length=" + (text == null ? 0 : text.length()) + ">";
        latestTranscriptAt = System.currentTimeMillis();
        latestInputKind = "typed";
        ringWritePosition = 0;
        ringBytes = 0;
        lastRms = 0;
        lastNoiseFloor = 0;
        lastGate = 0;
        decayedPeakRms = 0;
    }

    synchronized void captureBeforeFirstMutation(String taskId, String toolName, JSONObject args) {
        if (!isDebugBuild() || context == null) return;
        String safeTask = taskId == null ? "" : taskId.trim();
        if (safeTask.isEmpty() || safeTask.equals(lastCapturedTaskId)) return;
        lastCapturedTaskId = safeTask;

        try {
            File dir = incidentDir();
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;

            long now = System.currentTimeMillis();
            String stem = "incident-" + now + "-" + safeFilePart(toolName);
            File wav = new File(dir, stem + ".wav");
            File meta = new File(dir, stem + ".json");
            byte[] pcm = snapshotPcm();

            writeWav(wav, pcm);

            double rmsDb = dbfs(lastRms);
            double peakDb = dbfs(decayedPeakRms);
            double noiseDb = dbfs(lastNoiseFloor);
            double gateDb = dbfs(lastGate);
            double snrDb = peakDb - noiseDb;
            long transcriptAge = latestTranscriptAt <= 0 ? -1L : Math.max(0L, now - latestTranscriptAt);

            JSONObject json = new JSONObject()
                    .put("version", "0029")
                    .put("capturedAtMs", now)
                    .put("taskId", safeTask)
                    .put("tool", toolName == null ? "" : toolName)
                    .put("inputKind", latestInputKind)
                    .put("latestTranscript", latestTranscript)
                    .put("transcriptAgeMs", transcriptAge)
                    .put("audioDurationMs", pcm.length * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE))
                    .put("lastRmsDbfs", round1(rmsDb))
                    .put("recentPeakDbfs", round1(peakDb))
                    .put("noiseFloorDbfs", round1(noiseDb))
                    .put("gateDbfs", round1(gateDb))
                    .put("approxSnrDb", round1(snrDb))
                    .put("args", sanitizeArgs(args))
                    .put("wavFile", wav.getName());

            writeUtf8(meta, json.toString(2));
            prune(dir);

            Log.w(TAG, "Saved pre-mutation audio incident: " + wav.getAbsolutePath()
                    + " transcript=" + latestTranscript + " tool=" + toolName);
        } catch (Throwable error) {
            Log.w(TAG, "Audio incident capture failed: " + error.getMessage());
        } finally {
            decayedPeakRms = 0;
        }
    }

    private File incidentDir() {
        File external = context.getExternalFilesDir("audio-incidents");
        if (external != null) return external;
        return new File(context.getFilesDir(), "audio-incidents");
    }

    private synchronized byte[] snapshotPcm() {
        byte[] out = new byte[Math.max(0, ringBytes)];
        if (ringBytes <= 0) return out;
        int start = (ringWritePosition - ringBytes + MAX_RING_BYTES) % MAX_RING_BYTES;
        int first = Math.min(ringBytes, MAX_RING_BYTES - start);
        System.arraycopy(ring, start, out, 0, first);
        int remaining = ringBytes - first;
        if (remaining > 0) System.arraycopy(ring, 0, out, first, remaining);
        return out;
    }

    private static JSONObject sanitizeArgs(JSONObject args) {
        JSONObject out = new JSONObject();
        if (args == null) return out;
        try {
            Iterator<String> keys = args.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = args.opt(key);
                String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
                if (isSafeKey(lower) && (value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    out.put(key, value);
                } else if (value instanceof Number || value instanceof Boolean) {
                    out.put(key, value);
                } else if (value instanceof String) {
                    out.put(key, "<redacted length=" + ((String) value).length() + ">");
                } else if (value != null) {
                    out.put(key, "<redacted>");
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean isSafeKey(String key) {
        return "app".equals(key)
                || "package_name".equals(key)
                || "key".equals(key)
                || "element_id".equals(key)
                || "label".equals(key)
                || "id".equals(key)
                || "role".equals(key)
                || "direction".equals(key)
                || "distance".equals(key)
                || "condition".equals(key)
                || "coordinate_space".equals(key);
    }

    private static void writeUtf8(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void writeWav(File file, byte[] pcm) throws Exception {
        int dataLength = pcm == null ? 0 : pcm.length;
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(new byte[]{'R','I','F','F'});
            writeLeInt(out, 36 + dataLength);
            out.write(new byte[]{'W','A','V','E'});
            out.write(new byte[]{'f','m','t',' '});
            writeLeInt(out, 16);
            writeLeShort(out, 1);
            writeLeShort(out, 1);
            writeLeInt(out, SAMPLE_RATE);
            writeLeInt(out, SAMPLE_RATE * BYTES_PER_SAMPLE);
            writeLeShort(out, BYTES_PER_SAMPLE);
            writeLeShort(out, 16);
            out.write(new byte[]{'d','a','t','a'});
            writeLeInt(out, dataLength);
            if (dataLength > 0) out.write(pcm);
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void writeLeInt(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void writeLeShort(FileOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void prune(File dir) {
        File[] jsonFiles = dir.listFiles();
        if (jsonFiles == null) return;
        List<File> incidents = new ArrayList<File>();
        for (File file : jsonFiles) {
            if (file != null && file.isFile() && file.getName().startsWith("incident-")
                    && file.getName().endsWith(".json")) {
                incidents.add(file);
            }
        }
        Collections.sort(incidents, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = MAX_INCIDENTS; i < incidents.size(); i++) {
            File meta = incidents.get(i);
            String name = meta.getName();
            String stem = name.substring(0, name.length() - 5);
            try { meta.delete(); } catch (Exception ignored) {}
            try { new File(dir, stem + ".wav").delete(); } catch (Exception ignored) {}
        }
    }

    private static double dbfs(double value) {
        if (value <= 0.000001) return -96.0;
        return Math.max(-96.0, 20.0 * Math.log10(value));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String clip(String text, int max) {
        String value = text == null ? "" : text.trim();
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }

    private static String safeFilePart(String value) {
        String out = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        return out.isEmpty() ? "unknown" : out;
    }
}
