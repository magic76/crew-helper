package com.crewpocket.helper;

import org.json.JSONArray;
import org.json.JSONObject;

/** Run with a real org.json implementation (not Android's stub android.jar). */
public final class ConversationContextTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        WorkingContext context = new WorkingContext(true);
        context.beginUserTurn();
        context.setGoalHint("找附近咖啡店");
        context.observe("maps", "screen-a", "stable-a");
        context.recordAction("tap:A", "submitted");
        context.updateLastResult("STEP_FAILED");
        context.setPendingTask("WAIT_SCREEN_CHANGE");
        context.beginUserTurn();
        context.setGoalHint("不是這家，要有插座");
        JSONObject model = context.toModelJson();
        check(!model.has("goal"), "latest fragment must not masquerade as a complete goal");
        check("不是這家，要有插座".equals(model.getString("latestUserInput")), "latest correction kept verbatim");
        check("找附近咖啡店".equals(model.getJSONArray("recentUserInputs").getString(0)), "original request retained");
        check(model.getJSONArray("recentUserInputs").length() == 2, "two user inputs retained");
        check("tap:A".equals(model.getJSONArray("recentActionHistory").getString(0)), "previous attempt retained");
        check("STEP_FAILED".equals(model.getString("lastObservedResult")), "failure retained as historical evidence");
        check(!model.has("pendingTask"), "cancelled wait must not carry into next turn");
        check("maps".equals(model.getString("currentApp")), "screen identity retained");
        check(model.getString("contextPolicy").contains("historical evidence"), "history labelled as history");
        check(!model.has("lastActions") && !model.has("lastResult"), "historical fields are distinct");

        // A topic change is passed through to the model; Runtime never classifies
        // it as a correction or resumes the previous work.
        context.beginUserTurn();
        context.setGoalHint("開相機");
        model = context.toModelJson();
        check("開相機".equals(model.getString("latestUserInput")), "unrelated request passed through");
        check(!model.has("pendingTask") && !model.has("goal"), "topic change carries no active old goal");
        context.observe("camera", "screen-b", "stable-b");
        check("camera".equals(context.toModelJson().getString("currentApp")), "new observation replaces old app");

        for (int i = 0; i < 10; i++) {
            context.beginUserTurn();
            context.setGoalHint("input-" + i);
            context.recordAction("action-" + i, "STEP_OK");
        }
        model = context.toModelJson();
        JSONArray inputs = model.getJSONArray("recentUserInputs");
        JSONArray actions = model.getJSONArray("recentActionHistory");
        check(inputs.length() == 4, "bounded input history");
        check("input-6".equals(inputs.getString(0)), "oldest inputs evicted");
        check(actions.length() == 5, "bounded action history");
        check("action-5".equals(actions.getString(0)), "oldest actions evicted");
        StringBuilder longInput = new StringBuilder();
        for (int i = 0; i < 300; i++) longInput.append('x');
        context.setGoalHint(longInput.toString());
        check(context.toModelJson().getString("latestUserInput").length() == 160, "excerpts stay bounded");

        context.clear();
        model = context.toModelJson();
        check(!model.has("recentUserInputs") && !model.has("latestUserInput"), "call end clears input history");
        check(!model.has("recentActionHistory") && !model.has("lastObservedResult"), "call end clears action history");
        check(!model.has("currentApp") && !model.has("currentScreen"), "call end clears screen context");
        context.setGoalHint("one");
        context.resetTransientForNewGoal();
        check(!context.toModelJson().has("recentUserInputs"), "explicit reset clears retained excerpts");

        WorkingContext legacy = new WorkingContext(false);
        legacy.setGoalHint("first goal");
        legacy.observe("maps", "a");
        legacy.recordAction("tap", "STEP_OK");
        legacy.setPendingTask("WAIT");
        legacy.beginUserTurn();
        legacy.setGoalHint("second goal");
        JSONObject oldModel = legacy.toModelJson();
        check("second goal".equals(oldModel.getString("goal")), "legacy goal semantics retained");
        check(!oldModel.has("lastActions") && !oldModel.has("lastResult"), "legacy still clears action history");
        check(!oldModel.has("pendingTask") && !oldModel.has("recentUserInputs"), "legacy has no retained inputs");
        check("maps".equals(oldModel.getString("currentApp")), "legacy retains current app");

        UserActionScope scope = new UserActionScope();
        context.setGoalHint("傳訊息給小明：明天見");
        scope.updateFromUserText("傳訊息給小明：明天見");
        check(scope.canSend(), "explicit send allowed");
        scope.consumeSendAuthorization();
        context.beginUserTurn();
        context.setGoalHint("搜尋小明");
        scope.updateFromUserText("搜尋小明");
        check(!scope.canSend(), "retained history never restores send authorization");
        check(context.toModelJson().getJSONArray("recentUserInputs").length() == 2, "history can coexist with revoked permission");
        check(LivePrompt.forMode(true).equals(LivePrompt.SIMPLE), "simple prompt selected");
        check(LivePrompt.forMode(false).equals(LivePrompt.CORE), "legacy prompt selected");
        check(LivePrompt.SIMPLE.contains("verification=PENDING"), "pending evidence protection remains");
        System.out.println("PASS: " + checks + " conversation checks");
    }

    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
}
