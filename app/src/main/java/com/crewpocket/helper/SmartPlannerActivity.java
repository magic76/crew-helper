package com.crewpocket.helper;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Minimal 0130 validation surface.
 *
 * This intentionally stays separate from Gemini Live. Enter a multi-step goal,
 * then observe whether Crew Pocket Planner + Crew Helper Runtime can complete it.
 */
public final class SmartPlannerActivity extends Activity {
    private EditText goalInput;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private SmartPlannerLoopController controller;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Crew Smart Planner 0130");

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Smart Planner Agent Loop MVP");
        title.setTextSize(20f);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("Crew Pocket: Codex / gpt-5.6-terra (medium)\n"
                + "Fallback: Luna\n"
                + "Planner 只決定 semantic WHAT；Crew Helper Runtime 執行 HOW。\n"
                + "MVP 不允許 SEND / payment / OTP / credentials / coordinates。");
        hint.setTextSize(13f);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(8);
        root.addView(hint, hintParams);

        goalInput = new EditText(this);
        goalInput.setHint("例如：打開設定，找到藍牙，進入目前連線裝置的詳細資訊");
        goalInput.setMinLines(3);
        goalInput.setMaxLines(6);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(14);
        root.addView(goalInput, inputParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(10);
        root.addView(actions, actionsParams);

        startButton = new Button(this);
        startButton.setText("開始 Planner");
        actions.addView(startButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stopButton = new Button(this);
        stopButton.setText("停止");
        stopButton.setEnabled(false);
        actions.addView(stopButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ScrollView scroll = new ScrollView(this);
        statusView = new TextView(this);
        statusView.setText("準備完成。先確認 Crew Pocket server (:8000) 已啟動，且 Crew Helper 無障礙服務已開啟。");
        statusView.setTextSize(13f);
        statusView.setTextIsSelectable(true);
        statusView.setPadding(0, dp(10), 0, dp(20));
        scroll.addView(statusView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        controller = new SmartPlannerLoopController(this,
                new SmartPlannerLoopController.Listener() {
                    @Override public void onStatus(final String text) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { appendStatus(text); }
                        });
                    }

                    @Override public void onFinished(final SmartPlannerLoopController.Result result) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                appendStatus("\n=== " + result.status + " ===");
                                appendStatus(result.message);
                                appendStatus("plannerCalls=" + result.plannerCalls
                                        + " mutations=" + result.mutations
                                        + " model=" + result.model
                                        + (result.degraded ? " (degraded)" : "")
                                        + " elapsedMs=" + result.elapsedMs);
                                startButton.setEnabled(true);
                                stopButton.setEnabled(false);
                            }
                        });
                    }
                });

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                String goal = goalInput.getText() == null ? "" : goalInput.getText().toString().trim();
                if (goal.isEmpty()) {
                    appendStatus("請先輸入任務目標。");
                    return;
                }
                statusView.setText("Goal: " + goal);
                if (!controller.start(goal)) {
                    appendStatus("Planner 已在執行中或目標無效。");
                    return;
                }
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
            }
        });

        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                controller.cancel();
                stopButton.setEnabled(false);
                appendStatus("已要求停止 Planner。");
            }
        });

        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.cancel();
        super.onDestroy();
    }

    private void appendStatus(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return;
        CharSequence current = statusView.getText();
        statusView.setText((current == null || current.length() == 0 ? "" : current + "\n") + clean);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
