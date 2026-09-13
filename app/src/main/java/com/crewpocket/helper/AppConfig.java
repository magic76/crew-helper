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
    public static final String KEY_VOICE_INPUT_COMPANION =
            "voice_input_companion_enabled";
    public static final String KEY_VOICE_PRESET = "voice_preset";

    // 0091: voice and speaking personality are independent.
    public static final String KEY_PERSONALITY_MIGRATED = "personality_migrated_v1";
    public static final String KEY_PERSONALITY_VERBOSITY = "personality_verbosity";
    public static final String KEY_PERSONALITY_INITIATIVE = "personality_initiative";
    public static final String KEY_PERSONALITY_EXPRESSION = "personality_expression";
    public static final String KEY_PERSONALITY_EXPLANATION = "personality_explanation";
    public static final String KEY_PERSONALITY_HUMOR = "personality_humor";
    public static final String KEY_PERSONALITY_DECISION = "personality_decision";

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

    // 0096: AI voice input bar above the user's existing keyboard.
    public static boolean isVoiceInputCompanionEnabled(Context context) {
        return context != null
                && getPrefs(context).getBoolean(
                        KEY_VOICE_INPUT_COMPANION,
                        true);
    }

    public static void setVoiceInputCompanionEnabled(
            Context context,
            boolean enabled) {
        if (context == null) return;
        getPrefs(context).edit()
                .putBoolean(KEY_VOICE_INPUT_COMPANION, enabled)
                .apply();
        if (!enabled) {
            try {
                VoiceInputCompanion.getInstance(context).hide();
            } catch (Exception ignored) {}
            FocusedInputRuntime.clear();
        }
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



    // ── 0091. Independent speaking personality ──

    private static void ensurePersonalityMigrated(Context context) {
        if (context == null) return;
        SharedPreferences prefs = getPrefs(context);
        if (prefs.getBoolean(KEY_PERSONALITY_MIGRATED, false)) return;

        String tone = prefs.getString(KEY_LIVE_TONE, "warm");
        SharedPreferences.Editor editor = prefs.edit();

        if ("professional".equals(tone)) {
            editor.putString(KEY_PERSONALITY_VERBOSITY, "short");
            editor.putString(KEY_PERSONALITY_INITIATIVE, "balanced");
            editor.putString(KEY_PERSONALITY_EXPRESSION, "direct");
            editor.putString(KEY_PERSONALITY_EXPLANATION, "reason");
            editor.putString(KEY_PERSONALITY_HUMOR, "none");
            editor.putString(KEY_PERSONALITY_DECISION, "balanced");
        } else if ("lively".equals(tone)) {
            editor.putString(KEY_PERSONALITY_VERBOSITY, "balanced");
            editor.putString(KEY_PERSONALITY_INITIATIVE, "proactive");
            editor.putString(KEY_PERSONALITY_EXPRESSION, "lively");
            editor.putString(KEY_PERSONALITY_EXPLANATION, "reason");
            editor.putString(KEY_PERSONALITY_HUMOR, "light");
            editor.putString(KEY_PERSONALITY_DECISION, "balanced");
        } else if ("calm".equals(tone)) {
            editor.putString(KEY_PERSONALITY_VERBOSITY, "balanced");
            editor.putString(KEY_PERSONALITY_INITIATIVE, "quiet");
            editor.putString(KEY_PERSONALITY_EXPRESSION, "natural");
            editor.putString(KEY_PERSONALITY_EXPLANATION, "reason");
            editor.putString(KEY_PERSONALITY_HUMOR, "none");
            editor.putString(KEY_PERSONALITY_DECISION, "cautious");
        } else if ("urgent".equals(tone)) {
            editor.putString(KEY_PERSONALITY_VERBOSITY, "short");
            editor.putString(KEY_PERSONALITY_INITIATIVE, "proactive");
            editor.putString(KEY_PERSONALITY_EXPRESSION, "direct");
            editor.putString(KEY_PERSONALITY_EXPLANATION, "answer");
            editor.putString(KEY_PERSONALITY_HUMOR, "none");
            editor.putString(KEY_PERSONALITY_DECISION, "decisive");
        } else {
            editor.putString(KEY_PERSONALITY_VERBOSITY, "balanced");
            editor.putString(KEY_PERSONALITY_INITIATIVE, "balanced");
            editor.putString(KEY_PERSONALITY_EXPRESSION, "natural");
            editor.putString(KEY_PERSONALITY_EXPLANATION, "reason");
            editor.putString(KEY_PERSONALITY_HUMOR, "light");
            editor.putString(KEY_PERSONALITY_DECISION, "balanced");
        }

        editor.putBoolean(KEY_PERSONALITY_MIGRATED, true).apply();
    }

    private static String personalityValue(
            Context context,
            String key,
            String fallback,
            String a,
            String b,
            String c) {
        if (context == null) return fallback;
        ensurePersonalityMigrated(context);
        String value = getPrefs(context).getString(key, fallback);
        return a.equals(value) || b.equals(value) || c.equals(value)
                ? value : fallback;
    }

    private static void setPersonalityValue(
            Context context,
            String key,
            String value,
            String fallback,
            String a,
            String b,
            String c) {
        if (context == null) return;
        String clean = a.equals(value) || b.equals(value) || c.equals(value)
                ? value : fallback;
        getPrefs(context).edit()
                .putBoolean(KEY_PERSONALITY_MIGRATED, true)
                .putString(key, clean)
                .apply();
    }

    public static String getPersonalityVerbosity(Context context) {
        return personalityValue(
                context, KEY_PERSONALITY_VERBOSITY,
                "balanced", "short", "balanced", "detailed");
    }

    public static void setPersonalityVerbosity(Context context, String value) {
        setPersonalityValue(
                context, KEY_PERSONALITY_VERBOSITY, value,
                "balanced", "short", "balanced", "detailed");
    }

    public static String getPersonalityInitiative(Context context) {
        return personalityValue(
                context, KEY_PERSONALITY_INITIATIVE,
                "balanced", "quiet", "balanced", "proactive");
    }

    public static void setPersonalityInitiative(Context context, String value) {
        setPersonalityValue(
                context, KEY_PERSONALITY_INITIATIVE, value,
                "balanced", "quiet", "balanced", "proactive");
    }

    public static String getPersonalityExpression(Context context) {
        return personalityValue(
                context, KEY_PERSONALITY_EXPRESSION,
                "natural", "direct", "natural", "lively");
    }

    public static void setPersonalityExpression(Context context, String value) {
        setPersonalityValue(
                context, KEY_PERSONALITY_EXPRESSION, value,
                "natural", "direct", "natural", "lively");
    }

    public static String getPersonalityExplanation(Context context) {
        return personalityValue(
                context, KEY_PERSONALITY_EXPLANATION,
                "reason", "answer", "reason", "teach");
    }

    public static void setPersonalityExplanation(Context context, String value) {
        setPersonalityValue(
                context, KEY_PERSONALITY_EXPLANATION, value,
                "reason", "answer", "reason", "teach");
    }

    public static String getPersonalityHumor(Context context) {
        return personalityValue(
                context, KEY_PERSONALITY_HUMOR,
                "light", "none", "light", "playful");
    }

    public static void setPersonalityHumor(Context context, String value) {
        setPersonalityValue(
                context, KEY_PERSONALITY_HUMOR, value,
                "light", "none", "light", "playful");
    }

    public static String getPersonalityDecisionStyle(Context context) {
        return personalityValue(
                context, KEY_PERSONALITY_DECISION,
                "balanced", "cautious", "balanced", "decisive");
    }

    public static void setPersonalityDecisionStyle(Context context, String value) {
        setPersonalityValue(
                context, KEY_PERSONALITY_DECISION, value,
                "balanced", "cautious", "balanced", "decisive");
    }

    public static void applyPersonalityTemplate(Context context, String template) {
        if (context == null) return;

        String verbosity = "balanced";
        String initiative = "balanced";
        String expression = "natural";
        String explanation = "reason";
        String humor = "light";
        String decision = "balanced";

        if ("brief".equals(template)) {
            verbosity = "short";
            initiative = "quiet";
            expression = "direct";
            explanation = "answer";
            humor = "none";
            decision = "balanced";
        } else if ("work".equals(template)) {
            verbosity = "short";
            initiative = "proactive";
            expression = "direct";
            explanation = "reason";
            humor = "none";
            decision = "decisive";
        } else if ("chat".equals(template)) {
            verbosity = "detailed";
            initiative = "balanced";
            expression = "lively";
            explanation = "reason";
            humor = "playful";
            decision = "balanced";
        } else if ("teacher".equals(template)) {
            verbosity = "detailed";
            initiative = "proactive";
            expression = "natural";
            explanation = "teach";
            humor = "light";
            decision = "balanced";
        }

        getPrefs(context).edit()
                .putBoolean(KEY_PERSONALITY_MIGRATED, true)
                .putString(KEY_PERSONALITY_VERBOSITY, verbosity)
                .putString(KEY_PERSONALITY_INITIATIVE, initiative)
                .putString(KEY_PERSONALITY_EXPRESSION, expression)
                .putString(KEY_PERSONALITY_EXPLANATION, explanation)
                .putString(KEY_PERSONALITY_HUMOR, humor)
                .putString(KEY_PERSONALITY_DECISION, decision)
                .apply();
    }

    public static String getPersonalityInstruction(Context context) {
        if (context == null) return "自然、清楚、簡潔地回應。";

        StringBuilder out = new StringBuilder();

        String verbosity = getPersonalityVerbosity(context);
        if ("short".equals(verbosity)) {
            out.append("回答偏精簡，通常一到三句；除非使用者要求詳細，不主動長篇展開。");
        } else if ("detailed".equals(verbosity)) {
            out.append("回答可以較完整，主動補足重要背景與細節，但避免無關贅述。");
        } else {
            out.append("回答長度適中，依問題複雜度自然調整。");
        }

        String initiative = getPersonalityInitiative(context);
        if ("quiet".equals(initiative)) {
            out.append("只回應使用者當下需求，少主動延伸或追加建議。");
        } else if ("proactive".equals(initiative)) {
            out.append("在不打擾的前提下，可主動指出一個真正有用的下一步或風險。");
        } else {
            out.append("必要時補充一個有用的下一步，但不要過度主動。");
        }

        String expression = getPersonalityExpression(context);
        if ("direct".equals(expression)) {
            out.append("表達直接俐落，先講重點，少寒暄。");
        } else if ("lively".equals(expression)) {
            out.append("表達較有活力與自然情緒，但不可浮誇。");
        } else {
            out.append("表達自然、平衡、像日常對話。");
        }

        String explanation = getPersonalityExplanation(context);
        if ("answer".equals(explanation)) {
            out.append("預設先給答案，不主動展開推導；被追問時再解釋。");
        } else if ("teach".equals(explanation)) {
            out.append("需要解釋時採教學方式，循序說明並用簡短例子幫助理解。");
        } else {
            out.append("重要結論後簡短說明原因。");
        }

        String humor = getPersonalityHumor(context);
        if ("none".equals(humor)) {
            out.append("避免刻意幽默。");
        } else if ("playful".equals(humor)) {
            out.append("非嚴肅情境可自然帶一些幽默感，但不要搶過內容本身。");
        } else {
            out.append("合適時可以偶爾有一點自然幽默。");
        }

        String decision = getPersonalityDecisionStyle(context);
        if ("cautious".equals(decision)) {
            out.append("提出建議時偏保守，清楚指出不確定性與風險。");
        } else if ("decisive".equals(decision)) {
            out.append("有足夠資訊時給明確建議與下一步，不要只列選項；高風險事項仍保持保守。");
        } else {
            out.append("建議保持平衡，資訊足夠時可以指出較推薦的選項。");
        }

        out.append("以上只影響表達與一般偏好，不得改變工具授權、安全限制或驗證規則。");
        return out.toString();
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
