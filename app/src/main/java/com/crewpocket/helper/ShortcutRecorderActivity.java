package com.crewpocket.helper;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Review/test/save UI for a freshly recorded deterministic shortcut. */
public class ShortcutRecorderActivity extends Activity {
    private JSONArray steps = new JSONArray();
    private EditText triggerInput;
    private Button testButton;
    private Button saveButton;

    private int dp(float value) { return CrewTheme.dp(this, value); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
        getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);

        JSONObject pending = ShortcutRecorderRuntime.getInstance(this).loadPendingDraft();
        JSONArray pendingSteps = pending.optJSONArray("steps");
        if (pendingSteps == null || pendingSteps.length() == 0) {
            Toast.makeText(this, "沒有待儲存的錄製操作", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try { steps = new JSONArray(pendingSteps.toString()); }
        catch (Exception ignored) {}

        render(pending);
    }

    private void render(JSONObject pending) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CrewTheme.BG_PRIMARY);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("● 錄製快捷指令");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        content.addView(title);

        TextView note = new TextView(this);
        note.setText("Runtime 只保存 App package 與穩定的 Accessibility selector，不保存座標或你輸入的文字。");
        note.setTextSize(12);
        note.setTextColor(CrewTheme.TEXT_SECONDARY);
        note.setPadding(0, dp(6), 0, dp(16));
        content.addView(note);

        triggerInput = new EditText(this);
        triggerInput.setHint("觸發句，例如：Google 搜尋");
        triggerInput.setSingleLine(true);
        triggerInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        content.addView(triggerInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView stepsTitle = new TextView(this);
        stepsTitle.setText("錄製步驟");
        stepsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        stepsTitle.setTextColor(CrewTheme.TEAL_300);
        stepsTitle.setPadding(0, dp(18), 0, dp(8));
        content.addView(stepsTitle);

        TextView stepText = new TextView(this);
        stepText.setText(ShortcutPlanStore.describeSteps(steps));
        stepText.setTextSize(13);
        stepText.setTextColor(CrewTheme.TEXT_PRIMARY);
        stepText.setPadding(dp(14), dp(12), dp(14), dp(12));
        stepText.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 12));
        content.addView(stepText);

        int unsafe = pending.optInt("skippedUnsafe", 0);
        int unstable = pending.optInt("skippedUnstable", 0);
        boolean ignoredText = pending.optBoolean("ignoredTextInput", false);
        if (unsafe > 0 || unstable > 0 || ignoredText) {
            TextView warning = new TextView(this);
            StringBuilder w = new StringBuilder("錄製提示：");
            if (unsafe > 0) w.append("\n• 已略過 ").append(unsafe).append(" 個敏感操作");
            if (unstable > 0) w.append("\n• 已略過 ").append(unstable).append(" 個無穩定 selector 的點擊");
            if (ignoredText) w.append("\n• 文字輸入內容沒有被記錄");
            warning.setText(w.toString());
            warning.setTextSize(11);
            warning.setTextColor(CrewTheme.TEXT_SECONDARY);
            warning.setPadding(0, dp(12), 0, 0);
            content.addView(warning);
        }

        testButton = new Button(this);
        testButton.setText("▶ 測試");
        testButton.setAllCaps(false);
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testShortcut(); }
        });
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        buttonLp.setMargins(0, dp(18), 0, dp(8));
        content.addView(testButton, buttonLp);

        saveButton = new Button(this);
        saveButton.setText("儲存快捷指令");
        saveButton.setAllCaps(false);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveShortcut(); }
        });
        content.addView(saveButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ShortcutRecorderRuntime.getInstance(ShortcutRecorderActivity.this)
                        .clearPendingDraft();
                finish();
            }
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        cancelLp.setMargins(0, dp(8), 0, 0);
        content.addView(cancel, cancelLp);
    }

    private void testShortcut() {
        testButton.setEnabled(false);
        try {
            FloatingBubbleManager.getInstance(this)
                    .showCompactStatus("測試快捷指令", "正在執行 " + steps.length() + " 個步驟");
        } catch (Exception ignored) {}

        ShortcutExecutionRuntime.executeStepsAsync(this, steps,
                new ShortcutExecutionRuntime.Callback() {
                    @Override public void onComplete(boolean success, String detail) {
                        testButton.setEnabled(true);
                        try {
                            FloatingBubbleManager.getInstance(ShortcutRecorderActivity.this)
                                    .showCompactStatus(success ? "✓ 測試成功" : "✕ 測試失敗", detail);
                        } catch (Exception ignored) {}
                        Toast.makeText(ShortcutRecorderActivity.this,
                                detail, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void saveShortcut() {
        String trigger = triggerInput.getText().toString().trim();
        if (trigger.length() < 2) {
            Toast.makeText(this, "請輸入至少 2 個字的觸發句", Toast.LENGTH_LONG).show();
            return;
        }

        ShortcutPlanStore planStore = new ShortcutPlanStore(this);
        ShortcutPlanStore.Plan plan = planStore.save(trigger, steps);
        if (plan == null) {
            Toast.makeText(this, "快捷操作儲存失敗", Toast.LENGTH_LONG).show();
            return;
        }

        MemoryRuleStore.Rule rule = new MemoryRuleStore(this).save(
                trigger, ShortcutPlanStore.actionForPlan(plan.id));
        if (rule == null) {
            planStore.delete(plan.id);
            Toast.makeText(this, "觸發句儲存失敗", Toast.LENGTH_LONG).show();
            return;
        }

        ShortcutRecorderRuntime.getInstance(this).clearPendingDraft();
        Toast.makeText(this, "快捷指令已儲存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
