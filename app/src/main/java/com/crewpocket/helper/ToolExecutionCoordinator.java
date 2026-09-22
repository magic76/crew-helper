package com.crewpocket.helper;

import org.json.JSONObject;

/**
 * Routes an already-authorized Runtime tool to its execution domain.
 *
 * This class deliberately does NOT own user generations, mutation authorization,
 * AgentRuntimeV2 preflight, stability policy, task completion or verification.
 * NativeGeminiLiveClient completes those checks before and after this dispatcher.
 */
final class ToolExecutionCoordinator {
    interface Host {
        JSONObject getSelectedRegionForTool() throws Exception;
        JSONObject rememberAppGuidance(JSONObject args) throws Exception;
        JSONObject listAppGuidance() throws Exception;
        JSONObject inspectUiForTool(JSONObject args) throws Exception;
        JSONObject tapElement(JSONObject args) throws Exception;
        JSONObject waitForCondition(JSONObject args) throws Exception;
        JSONObject launchApp(JSONObject args) throws Exception;
        JSONObject swipe(JSONObject args) throws Exception;
        JSONObject tap(JSONObject args) throws Exception;
        JSONObject typeText(JSONObject args) throws Exception;
        JSONObject searchCurrentApp(JSONObject args) throws Exception;
        JSONObject commitSearch() throws Exception;
        JSONObject sendText(JSONObject args) throws Exception;
        JSONObject pressKey(JSONObject args) throws Exception;
        JSONObject startScreenMonitor(JSONObject args) throws Exception;
        JSONObject waitThenAction(JSONObject args) throws Exception;
        JSONObject conversationLoop(JSONObject args) throws Exception;
    }

    private final RuntimeToolExecutor runtimeToolExecutor;
    private final DeckRuntimeController deckRuntimeController;
    private final Host host;

    ToolExecutionCoordinator(
            RuntimeToolExecutor runtimeToolExecutor,
            DeckRuntimeController deckRuntimeController,
            Host host) {
        if (runtimeToolExecutor == null) {
            throw new IllegalArgumentException(
                    "runtimeToolExecutor required");
        }
        if (deckRuntimeController == null) {
            throw new IllegalArgumentException(
                    "deckRuntimeController required");
        }
        if (host == null) {
            throw new IllegalArgumentException("host required");
        }
        this.runtimeToolExecutor = runtimeToolExecutor;
        this.deckRuntimeController = deckRuntimeController;
        this.host = host;
    }

    JSONObject execute(String name, JSONObject args) throws Exception {
        JSONObject safeArgs = args == null ? new JSONObject() : args;

        if (SemanticPhoneAction.ERROR_TOOL.equals(name)) {
            return safeArgs;
        }
        if ("get_selected_region".equals(name)) {
            return host.getSelectedRegionForTool();
        }
        if ("remember_app_guidance".equals(name)) {
            return host.rememberAppGuidance(safeArgs);
        }
        if ("list_app_guidance".equals(name)) {
            return host.listAppGuidance();
        }
        if (runtimeToolExecutor.handles(name)) {
            if ("create_note".equals(name)
                    || "update_note".equals(name)) {
                PerformanceMetrics.recordTextRouteNotebook();
            }
            return runtimeToolExecutor.execute(name, safeArgs);
        }
        if ("inspect_ui".equals(name)) {
            return host.inspectUiForTool(safeArgs);
        }
        if ("tap_element".equals(name)) {
            return host.tapElement(safeArgs);
        }
        if ("wait".equals(name)) {
            return host.waitForCondition(safeArgs);
        }
        if ("launch_app".equals(name)) {
            return host.launchApp(safeArgs);
        }
        if ("swipe_screen".equals(name)) {
            return host.swipe(safeArgs);
        }
        if ("tap_screen".equals(name)) {
            return host.tap(safeArgs);
        }
        if ("type_text".equals(name)) {
            return host.typeText(safeArgs);
        }
        if ("search_current_app".equals(name)) {
            return host.searchCurrentApp(safeArgs);
        }
        if ("commit_search".equals(name)) {
            return host.commitSearch();
        }
        if ("send_text".equals(name)) {
            return host.sendText(safeArgs);
        }
        if ("press_key".equals(name)) {
            return host.pressKey(safeArgs);
        }
        if ("start_screen_monitor".equals(name)) {
            return host.startScreenMonitor(safeArgs);
        }
        if ("wait_then_action".equals(name)) {
            return host.waitThenAction(safeArgs);
        }
        if ("conversation_loop".equals(name)) {
            return host.conversationLoop(safeArgs);
        }
        if (deckRuntimeController.handles(name)) {
            return deckRuntimeController.execute(name, safeArgs);
        }

        return new JSONObject()
                .put("success", false)
                .put("error", "不支援的原生工具：" + name);
    }
}
