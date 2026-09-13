package com.crewpocket.helper;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Keyboard companion overlay.
 *
 * It appears immediately above the active IME when Accessibility confirms that
 * a non-sensitive editable field owns focus. It does not replace Gboard/Samsung
 * Keyboard and never submits text.
 */
final class VoiceInputCompanion {
    private static VoiceInputCompanion instance;

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View bar;
    private WindowManager.LayoutParams params;
    private int lastImeTop = -1;
    private String lastPackage = "";
    private AudioRecord recorder;
    private Thread recorderThread;
    private volatile boolean listening;

    static synchronized VoiceInputCompanion getInstance(Context context) {
        if (instance == null) {
            instance = new VoiceInputCompanion(
                    context.getApplicationContext());
        }
        return instance;
    }

    private VoiceInputCompanion(Context context) {
        this.context = context;
        this.windowManager = (WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
    }

    void update(
            final boolean shouldShow,
            final int imeTop,
            final String packageName) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!shouldShow
                        || !AppConfig.isVoiceInputCompanionEnabled(context)
                        || !canDrawOverlays()) {
                    hideInternal();
                    return;
                }

                int safeTop = Math.max(dp(48), imeTop);
                String pkg = packageName == null ? "" : packageName;

                if (bar == null) {
                    showInternal(safeTop, pkg);
                    return;
                }

                if (safeTop != lastImeTop) {
                    lastImeTop = safeTop;
                    params.y = Math.max(0, safeTop - dp(52));
                    try {
                        windowManager.updateViewLayout(bar, params);
                    } catch (Exception ignored) {}
                }
                lastPackage = pkg;
            }
        });
    }

    void hide() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                hideInternal();
            }
        });
    }

    private void showInternal(int imeTop, String packageName) {
        if (windowManager == null || !canDrawOverlays()) return;

        hideInternal();
        lastImeTop = imeTop;
        lastPackage = packageName == null ? "" : packageName;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(0, dp(4), 0, dp(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(244, 24, 24, 27));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#3F3F46"));
        root.setBackground(bg);

        ImageView dictate = makeHoldAction();
        root.addView(dictate, new LinearLayout.LayoutParams(dp(52), dp(40)));

        params = new WindowManager.LayoutParams(
                dp(52),
                dp(48),
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = Math.max(0, imeTop - dp(52));

        bar = root;
        try {
            windowManager.addView(bar, params);
        } catch (Exception error) {
            bar = null;
            params = null;
        }
    }

    private ImageView makeHoldAction() {
        ImageView view = new ImageView(context);
        view.setImageResource(R.drawable.crew_assistant_bubble);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(8), dp(6), dp(8), dp(6));
        view.setContentDescription("按住說話，放開後輸入");
        view.setClickable(true);
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                beginListening();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                endListening();
                return true;
            }
            return true;
        });

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(55, 19, 78, 74));
        bg.setCornerRadius(dp(11));
        view.setBackground(bg);
        return view;
    }

    private void beginListening() {
        JSONObjectSafe armed = JSONObjectSafe.from(
                FocusedInputRuntime.arm(context, "dictate"));
        if (!armed.success) {
            Toast.makeText(
                    context,
                    armed.message(),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int min = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(8192, min * 2);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            recorder.startRecording();
        } catch (Exception error) {
            FocusedInputRuntime.clear();
            Toast.makeText(context, "無法啟動麥克風", Toast.LENGTH_SHORT).show();
            return;
        }
        listening = true;
        recorderThread = new Thread(() -> {
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            short[] samples = new short[2048];
            while (listening && recorder != null) {
                int count = recorder.read(samples, 0, samples.length);
                if (count <= 0) continue;
                for (int i = 0; i < count; i++) {
                    audio.write(samples[i] & 0xff);
                    audio.write((samples[i] >> 8) & 0xff);
                }
            }
            sendToGemini(audio.toByteArray());
        }, "crew-gemini-ptt-recorder");
        recorderThread.start();
    }

    private void endListening() {
        if (!listening || recorder == null) return;
        listening = false;
        try { recorder.stop(); } catch (Exception ignored) {}
        recorder.release();
        recorder = null;
    }

    private void sendToGemini(final byte[] pcm) {
        new Thread(() -> {
            try {
                if (pcm == null || pcm.length < 1600) {
                    FocusedInputRuntime.clear();
                    return;
                }
                byte[] wav = wav(pcm);
                org.json.JSONObject part = new org.json.JSONObject();
                part.put("inline_data", new org.json.JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)));
                org.json.JSONObject prompt = new org.json.JSONObject().put("text",
                        "Transcribe the user's speech exactly. Detect Chinese or English automatically. "
                        + "Return only the transcription, no explanation, no translation, no punctuation changes.");
                org.json.JSONArray contents = new org.json.JSONArray().put(
                        new org.json.JSONObject().put("role", "user")
                                .put("parts", new org.json.JSONArray().put(prompt).put(part)));
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key="
                        + AppConfig.getGeminiApiKey(context));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] body = new org.json.JSONObject().put("contents", contents)
                        .toString().getBytes(StandardCharsets.UTF_8);
                connection.getOutputStream().write(body);
                InputStream input = connection.getResponseCode() >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                byte[] response = readAll(input);
                org.json.JSONObject json = new org.json.JSONObject(new String(response, StandardCharsets.UTF_8));
                String text = json.optJSONArray("candidates").getJSONObject(0)
                        .optJSONObject("content").optJSONArray("parts")
                        .getJSONObject(0).optString("text", "").trim();
                if (!text.isEmpty()) {
                    org.json.JSONObject result = FocusedInputRuntime.write(text, "insert");
                    if (!result.optBoolean("success", false)) showToast("語音已辨識，但無法寫入");
                }
                connection.disconnect();
            } catch (Exception error) {
                FocusedInputRuntime.clear();
                showToast("Gemini 語音辨識失敗");
            }
        }, "crew-gemini-transcribe").start();
    }

    private byte[] wav(byte[] pcm) {
        byte[] out = new byte[44 + pcm.length];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return out;
    }

    private byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toByteArray();
    }

    private void showToast(final String message) {
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private TextView makeAction(
            String text,
            View.OnClickListener listener) {
        TextView view = makeLabel(text);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.WHITE);
        view.setClickable(true);
        view.setOnClickListener(listener);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(30, 255, 255, 255));
        bg.setCornerRadius(dp(11));
        view.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0,
                dp(40),
                1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        view.setLayoutParams(lp);
        return view;
    }

    private TextView makeLabel(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(12.5f);
        view.setSingleLine(true);
        return view;
    }

    private void hideInternal() {
        if (bar != null) {
            try {
                windowManager.removeViewImmediate(bar);
            } catch (Exception ignored) {}
        }
        bar = null;
        params = null;
        lastImeTop = -1;
        lastPackage = "";
    }

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(context);
    }

    private int dp(float value) {
        return (int) (
                value
                        * context.getResources()
                                .getDisplayMetrics()
                                .density
                        + 0.5f);
    }

    /**
     * Tiny wrapper to keep UI code independent from org.json exception noise.
     */
    private static final class JSONObjectSafe {
        final boolean success;
        final String error;

        private JSONObjectSafe(boolean success, String error) {
            this.success = success;
            this.error = error == null ? "" : error;
        }

        static JSONObjectSafe from(org.json.JSONObject value) {
            if (value == null) {
                return new JSONObjectSafe(false, "INPUT_UNAVAILABLE");
            }
            return new JSONObjectSafe(
                    value.optBoolean("success", false),
                    value.optString("error", ""));
        }

        String message() {
            if ("SENSITIVE_INPUT_BLOCKED".equals(error)) {
                return "敏感輸入欄位不提供 AI 語音輸入";
            }
            if ("FOCUSED_INPUT_TARGET_CHANGED".equals(error)) {
                return "輸入欄位已改變，請重新點一下";
            }
            return "目前沒有可用的輸入欄位";
        }
    }
}
