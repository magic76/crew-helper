package com.crewpocket.helper;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Temporary privacy-bounded trace for diagnosing MEDIA:PLAY continuation.
 * It emits only fixed enums, booleans and counts. Never logs user text, query
 * text, screen labels, tool arguments, screenshots, replies or credentials.
 */
final class MediaFlowDiagnostic {
    private static final String TAG = "CrewMediaDiag";

    private MediaFlowDiagnostic() {}

    static boolean shouldTrace(String goalIntent, String action, String tool) {
        return "MEDIA:PLAY".equals(goalIntent)
                || isPlayAlias(action)
                || ("MEDIA:PLAY".equals(goalIntent)
                    && isSearchOrObservationTool(tool));
    }

    static void voiceTurn(String source, String goalIntent) {
        Log.i(TAG, "event=voice_turn source=" + sourceToken(source)
                + " goal=" + goalToken(goalIntent));
    }

    static void modelTool(
            String stage,
            String tool,
            String action,
            String goalIntent) {
        if (!shouldTrace(goalIntent, action, tool)) return;
        Log.i(TAG, "event=model_tool stage=" + stageToken(stage)
                + " tool=" + toolToken(tool)
                + " action=" + actionToken(action)
                + " goal=" + goalToken(goalIntent));
    }

    static void semanticResolution(
            String action,
            String runtimeName,
            String failureCode,
            String goalIntent) {
        if (!shouldTrace(goalIntent, action, SemanticPhoneAction.TOOL_NAME)) {
            return;
        }
        String outcome = SemanticPhoneAction.ERROR_TOOL.equals(runtimeName)
                ? failureToken(failureCode)
                : "RESOLVED";
        Log.i(TAG, "event=semantic_resolution action="
                + actionToken(action)
                + " outcome=" + outcome
                + " goal=" + goalToken(goalIntent));
    }

    static void toolGate(
            String gate,
            String tool,
            String action,
            String goalIntent) {
        if (!shouldTrace(goalIntent, action, tool)) return;
        Log.i(TAG, "event=tool_gate gate=" + gateToken(gate)
                + " tool=" + toolToken(tool)
                + " action=" + actionToken(action)
                + " goal=" + goalToken(goalIntent));
    }

    static void modelScreen(
            String tool,
            String goalIntent,
            String goalText,
            JSONObject screen) {
        if (!"MEDIA:PLAY".equals(goalIntent)
                || !isSearchOrObservationTool(tool)) return;

        JSONArray items = screen == null
                ? null : screen.optJSONArray("items");
        int count = items == null ? 0 : items.length();
        int entityVisible = 0;
        int entityActionable = 0;
        int playVisible = 0;
        int playActionable = 0;
        for (int i = 0; i < count; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String role = item.optString("role", "");
            String label = item.optString("label", "");
            String hint = item.optString("semanticHint", "");
            String can = item.optString("can", "");
            boolean actionable = ScreenItemPriorityPolicy.isActionable(
                    role, label, hint, can);
            if (MediaGoalUiPolicy.isGoalEntityLabel(label, goalText)) {
                entityVisible++;
                if (actionable) entityActionable++;
            }
            if (MediaPlaybackCompletionPolicy.isPlayControl(
                    label + " " + hint)) {
                playVisible++;
                if (actionable) playActionable++;
            }
        }
        Log.i(TAG, "event=model_screen tool=" + toolToken(tool)
                + " goal=MEDIA:PLAY items=" + count
                + " entity_visible=" + entityVisible
                + " entity_actionable=" + entityActionable
                + " play_visible=" + playVisible
                + " play_actionable=" + playActionable);
    }

    static void recoveryWithGoal(
            String reason,
            String goalIntent,
            String goalText,
            String action,
            boolean trustedPackage,
            boolean screenSuccess,
            List<MediaTapRecoveryPolicy.Candidate> candidates,
            String selectedTarget) {
        if (!shouldTrace(goalIntent, action, SemanticPhoneAction.TOOL_NAME)) {
            return;
        }
        int total = candidates == null ? 0 : candidates.size();
        int entityVisible = 0;
        int entityEligible = 0;
        int playVisible = 0;
        int playEligible = 0;
        if (candidates != null) {
            for (MediaTapRecoveryPolicy.Candidate candidate : candidates) {
                if (candidate == null) continue;
                boolean entity = MediaGoalUiPolicy.isGoalEntityLabel(
                        candidate.label, goalText);
                if (entity) {
                    entityVisible++;
                    if (!candidate.sensitive && candidate.enabled
                            && candidate.clickable
                            && candidate.confidence
                                    >= MediaTapRecoveryPolicy.MIN_CONFIDENCE) {
                        entityEligible++;
                    }
                }
                boolean play = MediaPlaybackCompletionPolicy.isPlayControl(
                        candidate.label + " " + candidate.semanticHint);
                if (play) {
                    playVisible++;
                    if (MediaGoalUiPolicy.isEligiblePlayCandidate(
                            candidate.role,
                            candidate.label,
                            candidate.semanticHint,
                            candidate.clickable,
                            candidate.enabled,
                            candidate.sensitive,
                            candidate.confidence)) {
                        playEligible++;
                    }
                }
            }
        }
        Log.i(TAG, "event=media_recovery reason=" + reasonToken(reason)
                + " goal=" + goalToken(goalIntent)
                + " action=" + actionToken(action)
                + " trusted_package=" + trustedPackage
                + " screen_success=" + screenSuccess
                + " accessibility_elements=" + total
                + " entity_visible=" + entityVisible
                + " entity_eligible=" + entityEligible
                + " play_visible=" + playVisible
                + " play_eligible=" + playEligible
                + " selected=" + targetKind(selectedTarget, goalText));
    }

    static void continuation(boolean searchAndPlay, boolean completedTargetSeen) {
        Log.i(TAG, "event=search_continuation goal=MEDIA:PLAY"
                + " search_and_play=" + searchAndPlay
                + " completed_target_seen=" + completedTargetSeen);
    }

    static void elementFallback(boolean started) {
        Log.i(TAG, "event=element_reference_fallback goal=MEDIA:PLAY"
                + " started=" + started);
    }

    static void playbackCompletion(
            String evidence,
            boolean musicActiveBefore,
            boolean musicActiveAfter,
            boolean uiPlaying) {
        Log.i(TAG, "event=playback_completion goal=MEDIA:PLAY"
                + " evidence=" + evidenceToken(evidence)
                + " music_active_before=" + musicActiveBefore
                + " music_active_after=" + musicActiveAfter
                + " ui_playing=" + uiPlaying);
    }

    static void observationLoop(
            boolean pendingMediaSearch,
            boolean blocked,
            int sameScreenCount) {
        Log.i(TAG, "event=observation_loop goal=MEDIA:PLAY"
                + " pending_search=" + pendingMediaSearch
                + " blocked=" + blocked
                + " same_screen_count=" + Math.max(0, sameScreenCount));
    }

    static void autonomy(String decision, int attempts, String evidence) {
        Log.i(TAG, "event=autonomy goal=MEDIA:PLAY"
                + " decision=" + stageToken(decision)
                + " attempts=" + Math.max(0, attempts)
                + " evidence=" + evidenceToken(evidence));
    }

    private static String targetKind(String target, String goalText) {
        String value = target == null ? "" : target.trim();
        if (value.isEmpty()) return "NONE";
        if (MediaPlaybackCompletionPolicy.isPlayControl(value)) {
            return "PLAY_CONTROL";
        }
        if (!goalText.isEmpty()
                && MediaGoalUiPolicy.isGoalEntityLabel(value, goalText)) {
            return "GOAL_ENTITY";
        }
        return "OTHER";
    }

    private static boolean isPlayAlias(String action) {
        String token = actionToken(action);
        return "PLAY".equals(token)
                || "PLAY_MUSIC".equals(token)
                || "START_PLAYBACK".equals(token)
                || "RESUME_PLAYBACK".equals(token);
    }

    private static boolean isSearchOrObservationTool(String tool) {
        String token = toolToken(tool);
        return "search_current_app".equals(token)
                || "commit_search".equals(token)
                || "inspect_ui".equals(token);
    }

    private static String goalToken(String value) {
        if (value == null || value.trim().isEmpty()) return "NONE";
        String token = value.trim().toUpperCase(Locale.ROOT);
        if ("MEDIA:PLAY".equals(token)
                || "MEDIA:PAUSE".equals(token)
                || "MEDIA:NEXT".equals(token)
                || "MEDIA:PREVIOUS".equals(token)
                || "NAVIGATION:START".equals(token)
                || "SEARCH:RESULT".equals(token)
                || "APP:OPEN".equals(token)
                || "MESSAGE:SEND".equals(token)
                || "TEXT:TYPE_ONLY".equals(token)) return token;
        return "OTHER";
    }

    private static String toolToken(String value) {
        if (value == null) return "OTHER";
        String token = value.trim();
        if ("phone_action".equals(token)
                || "inspect_ui".equals(token)
                || "search_current_app".equals(token)
                || "commit_search".equals(token)
                || "type_text".equals(token)
                || "tap_screen".equals(token)
                || "tap_element".equals(token)
                || "launch_app".equals(token)) return token;
        return "OTHER";
    }

    private static String actionToken(String value) {
        if (value == null) return "NONE";
        String token = value.trim().toUpperCase(Locale.ROOT);
        if ("TAP".equals(token) || "PLAY".equals(token)
                || "PLAY_MUSIC".equals(token)
                || "START_PLAYBACK".equals(token)
                || "RESUME_PLAYBACK".equals(token)
                || "OPEN_APP".equals(token) || "SEARCH".equals(token)
                || "COMMIT_SEARCH".equals(token) || "TYPE".equals(token)
                || "SCROLL".equals(token) || "BACK".equals(token)
                || "HOME".equals(token) || "TAKE_PHOTO".equals(token)) {
            return token;
        }
        return token.isEmpty() ? "NONE" : "OTHER";
    }

    private static String stageToken(String value) {
        if ("RECEIVED".equals(value)
                || "RESOLUTION_INPUT".equals(value)
                || "NONE".equals(value)
                || "RECOVER_ONCE".equals(value)
                || "EXHAUSTED".equals(value)) return value;
        return "OTHER";
    }

    private static String sourceToken(String value) {
        if ("NEW_INTENT".equals(value)
                || "MERGED_SEGMENT".equals(value)) return value;
        return "OTHER";
    }

    private static String reasonToken(String value) {
        if ("NOT_PHONE_ACTION".equals(value)
                || "NO_MEDIA_GOAL".equals(value)
                || "ACTION_NOT_RECOVERABLE".equals(value)
                || "SEMANTIC_TAP_ALREADY_VALID".equals(value)
                || "EXPLICIT_ELEMENT_HINT".equals(value)
                || "ELEMENT_REFERENCE_ACTIVE".equals(value)
                || "TRUSTED_SCREEN_UNAVAILABLE".equals(value)
                || "NO_UNAMBIGUOUS_CANDIDATE".equals(value)
                || "REPAIRED".equals(value)) return value;
        return "OTHER";
    }

    private static String gateToken(String value) {
        if ("OPERATIONAL_TURN_NOT_FINALIZED".equals(value)
                || "STALE_USER_INTENT".equals(value)
                || "FINISHED_INTENT".equals(value)
                || "APP_TEACH_HANDLED".equals(value)
                || "ELEMENT_REFERENCE_WAITING".equals(value)
                || "TASK_RECIPE_FAST_PATH".equals(value)
                || "ELEMENT_REFERENCE_AUTH_REQUIRED".equals(value)
                || "SEMANTIC_RESOLUTION_COMPLETED".equals(value)
                || "VOICE_PREFLIGHT_BLOCK".equals(value)
                || "AGENT_RUNTIME_PREFLIGHT_BLOCK".equals(value)
                || "STABILITY_PREFLIGHT_BLOCK".equals(value)
                || "PREFLIGHT_PASSED".equals(value)
                || "OBSERVE_REQUIRED".equals(value)) return value;
        return "OTHER";
    }

    private static String failureToken(String value) {
        if ("RESOLVED".equals(value)
                || "UNKNOWN_SEMANTIC_ACTION".equals(value)
                || "TARGET_REQUIRED".equals(value)
                || "BAD_ELEMENT_ID".equals(value)
                || "NUMERIC_TAP_NEEDS_ELEMENT_REFERENCE".equals(value)
                || "NONE".equals(value)) return value;
        return "OTHER";
    }

    private static String evidenceToken(String value) {
        if ("AUTONOMY_RECOVERY_EXHAUSTED".equals(value)
                || "NO_PROGRESS_LOOP".equals(value)
                || "SEARCH_COMMIT_DISPATCHED_RESULTS_NOT_CONFIRMED".equals(value)
                || "UNCHANGED_SCREEN_OBSERVED_TWICE".equals(value)
                || "UNKNOWN_SEMANTIC_ACTION".equals(value)
                || "MEDIA_PLAYBACK_BECAME_ACTIVE".equals(value)
                || "MEDIA_UI_PLAYING".equals(value)
                || "MEDIA_PLAY_CONTROL_EFFECT".equals(value)
                || "MEDIA_PLAY_CONTROL_VERIFIED".equals(value)
                || "NONE".equals(value)) return value;
        return "OTHER";
    }
}
