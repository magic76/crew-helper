package com.crewpocket.helper;

import android.content.Context;
import android.content.SharedPreferences;

public class AppConfig {
    public static final String PREFS_NAME = "crew_helper_config";
    public static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    public static final String KEY_VOICE_NAME = "live_voice_name";
    public static final String KEY_LOCAL_BRIDGE = "local_bridge_enabled";
    /** 0084: private per-install capability token for localhost:8766. */
    public static final String KEY_LOCAL_BRIDGE_TOKEN = "local_bridge_token";
    public static final String KEY_NOISE_MODE = "noise_mode";
    public static final String KEY_NOISE_SUPPRESSION = "noise_suppression";
    public static final String KEY_LIVE_TONE = "live_tone";
    public static final String KEY_INTERRUPTION_SENSITIVITY = "interruption_sensitivity";
    public static final String KEY_AUDIO_OUTPUT = "audio_output";
    public static final String KEY_VOICE_PRESET = "voice_preset";
    /** Maximum automatic Gemini tool-result cycles in one Live agent task. */
    public static final String KEY_AGENT_MAX_STEPS = "agent_max_steps";
    /** 0031: minutes without a new user instruction before Live returns to IDLE. 0 disables. */
    public static final String KEY_LIVE_IDLE_TIMEOUT_MINUTES = "live_idle_timeout_minutes";

    // 0025: Always-On infra. These are runtime/infra preferences, not LLM state.
    public static final String KEY_ALWAYS_ON_ENABLED = "always_on_enabled";
    public static final String KEY_PICOVOICE_ACCESS_KEY = "picovoice_access_key";
    public static final String KEY_WAKE_PHRASE = "wake_phrase";
    public static final String KEY_WAKE_SENSITIVITY = "wake_sensitivity";

    public static final String DEFAULT_VOICE = "Kore";
    public static final String DEFAULT_WAKE_PHRASE = "小酷小酷";
    private static final String LEGACY_WAKE_PHRASE = "小歪小歪";
    private static final String PREVIOUS_WAKE_PHRASE = "嘿 小歪";

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

    // ── 2. Gemini Live Voice Persona ──
    public static String getVoiceName(Context context) {
        if (context == null) return DEFAULT_VOICE;
        return getPrefs(context).getString(KEY_VOICE_NAME, DEFAULT_VOICE);
    }

    public static void setVoiceName(Context context, String voice) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_VOICE_NAME, voice == null ? DEFAULT_VOICE : voice.trim()).apply();
    }

    // ── 4. App-internal Runtime Bridge (:8766) ──
    public static boolean isLocalBridgeEnabled(Context context) {
        if (context == null) return true;
        return getPrefs(context).getBoolean(KEY_LOCAL_BRIDGE, true);
    }

    public static void setLocalBridgeEnabled(Context context, boolean enabled) {
        if (context == null) return;
        getPrefs(context).edit().putBoolean(KEY_LOCAL_BRIDGE, enabled).apply();
    }

    /**
     * 0084: localhost is not an identity boundary on Android. Other apps such
     * as Termux can reach 127.0.0.1:8766, so every Runtime request carries a
     * private per-install capability token stored in Crew Helper app storage.
     *
     * The token is intentionally never exposed in UI, logs, model context, or
     * public bridge responses.
     */
    public static synchronized String getLocalBridgeToken(Context context) {
        if (context == null) return "";
        SharedPreferences prefs = getPrefs(context);
        String token = prefs.getString(KEY_LOCAL_BRIDGE_TOKEN, "");
        if (token == null || token.length() < 32) {
            token = java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(KEY_LOCAL_BRIDGE_TOKEN, token).commit();
        }
        return token;
    }

    public static boolean isLocalBridgeTokenValid(Context context, String candidate) {
        if (context == null || candidate == null) return false;
        String expected = getLocalBridgeToken(context);
        if (expected.length() != candidate.length() || expected.isEmpty()) return false;
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ candidate.charAt(i);
        }
        return diff == 0;
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

    // 0031. Live idle timeout
    public static int getLiveIdleTimeoutMinutes(Context context) {
        if (context == null) return 2;
        return Math.max(0, Math.min(30,
                getPrefs(context).getInt(KEY_LIVE_IDLE_TIMEOUT_MINUTES, 2)));
    }

    public static void setLiveIdleTimeoutMinutes(Context context, int minutes) {
        if (context == null) return;
        getPrefs(context).edit().putInt(
                KEY_LIVE_IDLE_TIMEOUT_MINUTES,
                Math.max(0, Math.min(30, minutes))).apply();
    }

    // ── 0025. Always-On Runtime ──
    public static boolean isAlwaysOnEnabled(Context context) {
        return context != null && getPrefs(context).getBoolean(KEY_ALWAYS_ON_ENABLED, false);
    }

    public static void setAlwaysOnEnabled(Context context, boolean enabled) {
        if (context == null) return;
        getPrefs(context).edit().putBoolean(KEY_ALWAYS_ON_ENABLED, enabled).apply();
    }

    public static String getPicovoiceAccessKey(Context context) {
        if (context == null) return "";
        return getPrefs(context).getString(KEY_PICOVOICE_ACCESS_KEY, "").trim();
    }

    public static void setPicovoiceAccessKey(Context context, String key) {
        if (context == null) return;
        getPrefs(context).edit().putString(KEY_PICOVOICE_ACCESS_KEY, key == null ? "" : key.trim()).apply();
    }

    public static String getWakePhrase(Context context) {
        if (context == null) return DEFAULT_WAKE_PHRASE;
        String phrase = getPrefs(context).getString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE);
        String clean = phrase == null ? "" : phrase.trim();
        // Migrate installations that still have the previous hard-coded phrase.
        if (clean.isEmpty()
                || LEGACY_WAKE_PHRASE.equals(clean)
                || PREVIOUS_WAKE_PHRASE.equals(clean)) {
            if (LEGACY_WAKE_PHRASE.equals(clean)
                    || PREVIOUS_WAKE_PHRASE.equals(clean)) {
                getPrefs(context).edit().putString(KEY_WAKE_PHRASE, DEFAULT_WAKE_PHRASE).apply();
            }
            return DEFAULT_WAKE_PHRASE;
        }
        return clean;
    }

    public static void setWakePhrase(Context context, String phrase) {
        if (context == null) return;
        String clean = phrase == null || phrase.trim().isEmpty() ? DEFAULT_WAKE_PHRASE : phrase.trim();
        getPrefs(context).edit().putString(KEY_WAKE_PHRASE, clean).apply();
    }

    public static int getWakeSensitivity(Context context) {
        if (context == null) return 65;
        return Math.max(5, Math.min(95, getPrefs(context).getInt(KEY_WAKE_SENSITIVITY, 65)));
    }

    public static void setWakeSensitivity(Context context, int value) {
        if (context == null) return;
        getPrefs(context).edit().putInt(KEY_WAKE_SENSITIVITY, Math.max(5, Math.min(95, value))).apply();
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
