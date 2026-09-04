package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

public class AppConfig {
    public static final String PREFS_NAME = "crew_helper_config";
    public static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    public static final String KEY_SERVER_URL = "custom_server_url";
    public static final String KEY_VOICE_NAME = "live_voice_name";
    public static final String KEY_LOCAL_BRIDGE = "local_bridge_enabled";
    public static final String KEY_NOISE_MODE = "noise_mode";
    public static final String KEY_NOISE_SUPPRESSION = "noise_suppression";
    public static final String KEY_LIVE_TONE = "live_tone";
    public static final String KEY_INTERRUPTION_SENSITIVITY = "interruption_sensitivity";
    public static final String KEY_AUDIO_OUTPUT = "audio_output";
    public static final String KEY_VOICE_PRESET = "voice_preset";
    /** Maximum automatic Gemini tool-result cycles in one Live agent task. */
    public static final String KEY_AGENT_MAX_STEPS = "agent_max_steps";

    public static final String DEFAULT_VOICE = "Kore";
    public static final String DEFAULT_SERVER = "http://127.0.0.1:8000";

    public static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── 1. Gemini API Key (BYOK) ──
    public static String getGeminiApiKey(Context context) {
        if (context == null) return "";
        String key = getPrefs(context).getString(KEY_GEMINI_API_KEY, "");
        if (key.isEmpty()) {
            key = context.getSharedPreferences("crew_native_live", Context.MODE_PRIVATE).getString("gemini_live_key", "");
        }
        if (key.isEmpty()) {
            key = context.getSharedPreferences("com.crewpocket.helper.NativeLiveActivity", Context.MODE_PRIVATE).getString("gemini_live_key", "");
        }
        return key;
    }

    public static void setGeminiApiKey(Context context, String key) {
        if (context == null) return;
        String cleanKey = key == null ? "" : key.trim();
        getPrefs(context).edit().putString(KEY_GEMINI_API_KEY, cleanKey).apply();
        context.getSharedPreferences("crew_native_live", Context.MODE_PRIVATE).edit().putString("gemini_live_key", cleanKey).apply();
    }

    // ── 2. Custom Server URL (Connected vs Standalone Mode) ──
    public static String getServerUrl(Context context) {
        if (context == null) return "";
        return getPrefs(context).getString(KEY_SERVER_URL, "");
    }

    public static void setServerUrl(Context context, String url) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_SERVER_URL, url == null ? "" : url.trim()).apply();
    }

    public static boolean isStandaloneMode(Context context) {
        String url = getServerUrl(context);
        return url == null || url.trim().isEmpty();
    }

    // ── 3. Gemini Live Voice Persona ──
    public static String getVoiceName(Context context) {
        if (context == null) return DEFAULT_VOICE;
        return getPrefs(context).getString(KEY_VOICE_NAME, DEFAULT_VOICE);
    }

    public static void setVoiceName(Context context, String voice) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_VOICE_NAME, voice == null ? DEFAULT_VOICE : voice.trim()).apply();
    }

    // ── 4. Local Bridge Automation (:8766) ──
    public static boolean isLocalBridgeEnabled(Context context) {
        if (context == null) return true;
        return getPrefs(context).getBoolean(KEY_LOCAL_BRIDGE, true);
    }

    public static void setLocalBridgeEnabled(Context context, boolean enabled) {
        if (context == null) return;
        getPrefs(context).edit().putBoolean(KEY_LOCAL_BRIDGE, enabled).apply();
    }

    // ── 5. Voice environment: auto, quiet, or noisy ──
    public static String getNoiseMode(Context context) {
        if (context == null) return "auto";
        String mode = getPrefs(context).getString(KEY_NOISE_MODE, "auto");
        return "quiet".equals(mode) || "noisy".equals(mode) ? mode : "auto";
    }

    public static void setNoiseMode(Context context, String mode) {
        if (context == null) return;
        String clean = "quiet".equals(mode) || "noisy".equals(mode) ? mode : "auto";
        getPrefs(context).edit().putString(KEY_NOISE_MODE, clean).apply();
    }

    public static int getNoiseSuppression(Context context) {
        if (context == null) return 35;
        int value = getPrefs(context).getInt(KEY_NOISE_SUPPRESSION, 35);
        return Math.max(0, Math.min(100, value));
    }

    public static void setNoiseSuppression(Context context, int value) {
        if (context == null) return;
        getPrefs(context).edit().putInt(KEY_NOISE_SUPPRESSION, Math.max(0, Math.min(100, value))).apply();
    }

    // ── 6. Live speaking style (applied at the next session setup) ──
    public static String getLiveTone(Context context) {
        if (context == null) return "warm";
        String tone = getPrefs(context).getString(KEY_LIVE_TONE, "warm");
        return isLiveTone(tone) ? tone : "warm";
    }

    public static void setLiveTone(Context context, String tone) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_LIVE_TONE, isLiveTone(tone) ? tone : "warm").apply();
    }

    // ── 7. Barge-in and audio route ──
    public static int getInterruptionSensitivity(Context context) {
        if (context == null) return 55;
        return Math.max(0, Math.min(100, getPrefs(context).getInt(KEY_INTERRUPTION_SENSITIVITY, 55)));
    }

    public static void setInterruptionSensitivity(Context context, int value) {
        if (context == null) return;
        getPrefs(context).edit().putInt(KEY_INTERRUPTION_SENSITIVITY, Math.max(0, Math.min(100, value))).apply();
    }

    /** "call" keeps AEC-friendly communication routing; "media" follows media volume/devices. */
    public static String getAudioOutput(Context context) {
        if (context == null) return "call";
        return "media".equals(getPrefs(context).getString(KEY_AUDIO_OUTPUT, "call")) ? "media" : "call";
    }

    public static void setAudioOutput(Context context, String output) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_AUDIO_OUTPUT, "media".equals(output) ? "media" : "call").apply();
    }

    public static String getVoicePreset(Context context) {
        if (context == null) return "custom";
        return getPrefs(context).getString(KEY_VOICE_PRESET, "custom");
    }

    public static void applyVoicePreset(Context context, String preset, String voice, String tone) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_VOICE_PRESET, preset == null ? "custom" : preset)
                .putString(KEY_VOICE_NAME, voice == null ? DEFAULT_VOICE : voice)
                .putString(KEY_LIVE_TONE, isLiveTone(tone) ? tone : "warm").apply();
    }

    // ── 8. Live Agent loop ──
    public static int getAgentMaxSteps(Context context) {
        if (context == null) return 20;
        return Math.max(1, Math.min(100, getPrefs(context).getInt(KEY_AGENT_MAX_STEPS, 20)));
    }

    public static void setAgentMaxSteps(Context context, int steps) {
        if (context == null) return;
        getPrefs(context).edit().putInt(KEY_AGENT_MAX_STEPS, Math.max(1, Math.min(100, steps))).apply();
    }

    private static boolean isLiveTone(String tone) {
        return "natural".equals(tone) || "warm".equals(tone) || "lively".equals(tone)
                || "professional".equals(tone) || "calm".equals(tone) || "urgent".equals(tone);
    }

    // ── 9. App Language (Bilingual: "auto", "zh", "en") ──
    public static final String KEY_LANGUAGE = "app_language";

    public static String getLanguage(Context context) {
        if (context == null) return "auto";
        return getPrefs(context).getString(KEY_LANGUAGE, "auto");
    }

    public static void setLanguage(Context context, String lang) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_LANGUAGE, lang == null ? "auto" : lang.trim()).apply();
    }

    // ── 10. User-defined Custom System Prompt ──
    public static final String KEY_CUSTOM_PROMPT = "custom_system_prompt";

    public static String getCustomSystemPrompt(Context context) {
        if (context == null) return "";
        return getPrefs(context).getString(KEY_CUSTOM_PROMPT, "");
    }

    public static void setCustomSystemPrompt(Context context, String prompt) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_CUSTOM_PROMPT, prompt == null ? "" : prompt.trim()).apply();
    }
}
