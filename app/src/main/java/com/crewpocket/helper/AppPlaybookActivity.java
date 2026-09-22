package com.crewpocket.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

/** User-facing management page for per-app Crew operational playbooks. */
public class AppPlaybookActivity extends Activity {
    private AppPlaybookStore store;
    private AppAutonomyStore autonomyStore;
    private AppCatalog appCatalog;
    private LinearLayout content;

    private int dp(float value) { return CrewTheme.dp(this, value); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AppPlaybookStore(this);
        autonomyStore = new AppAutonomyStore(this);
        appCatalog = new AppCatalog(this);
        appCatalog.prewarm();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
            getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        }
        getWindow().getDecorView().setBackgroundColor(CrewTheme.BG_PRIMARY);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(CrewTheme.BG_PRIMARY);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(26), dp(20), dp(30));
        scroll.addView(content);
        setContentView(scroll);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null) render();
    }

    private void render() {
        content.removeAllViews();

        TextView back = new TextView(this);
        back.setText(I18n.get(this, "‹ 返回設定", "‹ Back to Settings"));
        back.setTextSize(13);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(CrewTheme.INDIGO_400);
        back.setPadding(0, 0, 0, dp(12));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        content.addView(back);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        CrewIconView icon = new CrewIconView(this);
        icon.setIcon(CrewIcons.BRAIN, CrewTheme.TEAL_300);
        icon.setIconScale(0.66f);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(I18n.get(this, "App 經驗", "App Playbooks"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        text.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText(I18n.get(this,
                "Crew 對不同 App 的局部操作手冊",
                "Crew's app-local operating guidance"));
        subtitle.setTextSize(11);
        subtitle.setTextColor(CrewTheme.TEXT_SECONDARY);
        text.addView(subtitle);
        heading.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(heading);

        TextView explanation = new TextView(this);
        explanation.setText(I18n.get(this,
                "除了操作經驗，你也可以把信任的 App 加入「自主操作」白名單。白名單讓 Crew 對低風險點擊、搜尋與導航更果斷，不會因定位稍有歧義就停下問你；付款、購買、轉帳、帳號/密碼、刪除、取消訂單/預約/行程與訊息送出授權仍不會被繞過。",
                "You can also add trusted apps to the Autonomy whitelist. Trusted apps let Crew resolve low-risk taps, search and navigation more decisively instead of stopping on minor ambiguity. Payment, purchase, transfer, account/credential, deletion, trip/order/booking cancellation and message-send authorization are never bypassed."));
        explanation.setTextSize(11);
        explanation.setTextColor(CrewTheme.TEXT_SECONDARY);
        explanation.setLineSpacing(dp(2), 1.08f);
        explanation.setPadding(0, dp(14), 0, dp(12));
        content.addView(explanation);

        TextView stats = new TextView(this);
        stats.setText(I18n.get(this,
                "自訂：" + store.learnedAppCount() + " 個 App · "
                        + store.learnedRuleCount() + " 條經驗 · 自主 "
                        + autonomyStore.trustedCount() + " 個",
                "Learned: " + store.learnedAppCount() + " apps · "
                        + store.learnedRuleCount() + " rules · "
                        + autonomyStore.trustedCount() + " trusted"));
        stats.setTextSize(11);
        stats.setTextColor(CrewTheme.TEAL_300);
        stats.setPadding(dp(12), dp(9), dp(12), dp(9));
        stats.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 12));
        content.addView(stats);

        Button add = new Button(this);
        add.setAllCaps(false);
        add.setText(I18n.get(this, "＋ 新增 App 經驗", "+ Add App Guidance"));
        add.setTextSize(13);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setTextColor(Color.WHITE);
        add.setBackground(CrewTheme.createGradientButton(
                this, CrewTheme.TEAL_500, CrewTheme.INDIGO_500, 14));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAppPicker(); }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        addLp.setMargins(0, dp(12), 0, dp(7));
        content.addView(add, addLp);

        Button addTrusted = new Button(this);
        addTrusted.setAllCaps(false);
        addTrusted.setText(I18n.get(
                this, "＋ 新增自主操作 App", "+ Add Trusted Autonomy App"));
        addTrusted.setTextSize(12);
        addTrusted.setTypeface(Typeface.DEFAULT_BOLD);
        addTrusted.setTextColor(CrewTheme.TEAL_300);
        addTrusted.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_TEAL, 14));
        addTrusted.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showAutonomyAppPicker();
            }
        });
        LinearLayout.LayoutParams trustedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        trustedLp.setMargins(0, 0, 0, dp(18));
        content.addView(addTrusted, trustedLp);

        TextView section = new TextView(this);
        section.setText(I18n.get(this, "已知 App", "KNOWN APPS"));
        section.setTextSize(11);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        section.setTextColor(CrewTheme.INDIGO_400);
        section.setPadding(dp(4), 0, 0, dp(8));
        content.addView(section);

        ArrayList<String> packages = store.profilePackages();
        HashSet<String> packageSet = new HashSet<String>(packages);
        for (String pkg : autonomyStore.trustedPackages()) {
            if (packageSet.add(pkg)) packages.add(pkg);
        }
        Collections.sort(packages, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return AppRuntimeRegistry.displayName(
                                AppPlaybookActivity.this, a)
                        .compareToIgnoreCase(
                                AppRuntimeRegistry.displayName(
                                        AppPlaybookActivity.this, b));
            }
        });
        if (packages.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(I18n.get(this,
                    "目前還沒有 App 經驗。可以從上方新增，或在使用 App 時直接用語音教 Crew。",
                    "No app playbooks yet. Add one here or teach Crew by voice while using an app."));
            empty.setTextSize(12);
            empty.setTextColor(CrewTheme.TEXT_SECONDARY);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            content.addView(empty);
            return;
        }

        for (String pkg : packages) content.addView(makeAppCard(pkg));
    }

    private View makeAppCard(final String packageName) {
        AppRuntimeAdapter adapter = AppRuntimeRegistry.forPackage(packageName);
        JSONArray rules = store.rulesFor(packageName);
        String label = store.labelFor(packageName);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(13), dp(11), dp(12), dp(11));
        card.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 14));
        card.setClickable(true);
        card.setFocusable(true);

        CrewIconView icon = new CrewIconView(this);
        icon.setIcon(CrewIcons.PHONE_ACTIONS,
                adapter != null ? CrewTheme.TEAL_300 : CrewTheme.INDIGO_400);
        icon.setIconScale(0.58f);
        card.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(42)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(label.isEmpty() ? packageName : label);
        name.setTextSize(13);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(CrewTheme.TEXT_PRIMARY);
        info.addView(name);

        boolean trustedAutonomy =
                autonomyStore.isTrusted(packageName);
        String summary;
        if (adapter != null && rules.length() > 0) {
            summary = I18n.get(this,
                    "內建 Runtime · " + rules.length() + " 條自訂經驗",
                    "Built-in Runtime · " + rules.length() + " learned rules");
        } else if (adapter != null) {
            summary = I18n.get(this, "內建 Runtime 規則", "Built-in Runtime guidance");
        } else {
            summary = rules.length() + " " + I18n.get(this, "條自訂經驗", "learned rules");
        }
        if (trustedAutonomy) {
            summary = I18n.get(this, "自主操作 ON · ", "Autonomy ON · ")
                    + summary;
        }
        TextView detail = new TextView(this);
        detail.setText(summary);
        detail.setTextSize(10.5f);
        detail.setTextColor(adapter != null ? CrewTheme.TEAL_300 : CrewTheme.TEXT_SECONDARY);
        info.addView(detail);

        TextView pkg = new TextView(this);
        pkg.setText(packageName);
        pkg.setTextSize(9.5f);
        pkg.setTextColor(CrewTheme.TEXT_MUTED);
        pkg.setSingleLine(true);
        pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(pkg);
        card.addView(info, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(CrewTheme.TEXT_MUTED);
        chevron.setGravity(Gravity.CENTER);
        card.addView(chevron, new LinearLayout.LayoutParams(dp(22), dp(42)));

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showProfile(packageName); }
        });

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(7));
        outer.addView(card, lp);
        return outer;
    }

    private void showProfile(final String packageName) {
        final String label = store.labelFor(packageName);
        final AppRuntimeAdapter adapter = AppRuntimeRegistry.forPackage(packageName);
        final JSONArray rules = store.rulesFor(packageName);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(8));
        scroll.addView(root);

        TextView pkg = new TextView(this);
        pkg.setText(packageName);
        pkg.setTextSize(9.5f);
        pkg.setTextColor(CrewTheme.TEXT_MUTED);
        pkg.setPadding(0, 0, 0, dp(10));
        root.addView(pkg);

        final LinearLayout autonomyRow = new LinearLayout(this);
        autonomyRow.setOrientation(LinearLayout.HORIZONTAL);
        autonomyRow.setGravity(Gravity.CENTER_VERTICAL);
        autonomyRow.setPadding(dp(11), dp(10), dp(10), dp(10));
        autonomyRow.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_TEAL, 11));

        LinearLayout autonomyText = new LinearLayout(this);
        autonomyText.setOrientation(LinearLayout.VERTICAL);
        TextView autonomyTitle = new TextView(this);
        autonomyTitle.setText(I18n.get(
                this, "自主操作白名單", "Trusted Autonomy"));
        autonomyTitle.setTextSize(12);
        autonomyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        autonomyTitle.setTextColor(CrewTheme.TEXT_PRIMARY);
        autonomyText.addView(autonomyTitle);
        TextView autonomyDetail = new TextView(this);
        autonomyDetail.setText(I18n.get(
                this,
                "低風險操作自行判斷與 fallback；敏感操作仍受保護",
                "Self-resolve low-risk actions; sensitive actions stay protected"));
        autonomyDetail.setTextSize(9.5f);
        autonomyDetail.setTextColor(CrewTheme.TEXT_SECONDARY);
        autonomyText.addView(autonomyDetail);
        autonomyRow.addView(autonomyText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView autonomyState = new TextView(this);
        autonomyState.setTextSize(10);
        autonomyState.setTypeface(Typeface.DEFAULT_BOLD);
        autonomyState.setGravity(Gravity.CENTER);
        autonomyState.setPadding(dp(9), dp(4), dp(9), dp(4));
        autonomyRow.addView(autonomyState);
        final boolean initialAutonomy =
                autonomyStore.isTrusted(packageName);
        updateAutonomyStateView(autonomyState, initialAutonomy);
        autonomyRow.setTag(Boolean.valueOf(initialAutonomy));
        autonomyRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean current =
                        v.getTag() instanceof Boolean
                                && ((Boolean) v.getTag()).booleanValue();
                boolean next = !current;
                autonomyStore.setTrusted(packageName, next);
                v.setTag(Boolean.valueOf(next));
                updateAutonomyStateView(autonomyState, next);
                Toast.makeText(
                        AppPlaybookActivity.this,
                        next
                                ? I18n.get(
                                        AppPlaybookActivity.this,
                                        "已開啟自主操作",
                                        "Trusted autonomy enabled")
                                : I18n.get(
                                        AppPlaybookActivity.this,
                                        "已關閉自主操作",
                                        "Trusted autonomy disabled"),
                        Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams autonomyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        autonomyLp.setMargins(0, 0, 0, dp(12));
        root.addView(autonomyRow, autonomyLp);

        if (adapter != null) {
            TextView builtInTitle = sectionLabel(I18n.get(
                    this, "內建 Runtime 經驗", "BUILT-IN RUNTIME GUIDANCE"));
            root.addView(builtInTitle);
            TextView builtIn = bodyText(adapter.displayGuidance(this));
            builtIn.setBackground(CrewTheme.createCard(
                    this, Color.argb(32, 20, 184, 166), CrewTheme.BORDER_TEAL, 10));
            root.addView(builtIn);
        }

        TextView learnedTitle = sectionLabel(I18n.get(
                this, "自訂經驗", "LEARNED GUIDANCE"));
        learnedTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(learnedTitle);

        if (rules.length() == 0) {
            TextView none = bodyText(I18n.get(this,
                    "尚未新增自訂經驗。",
                    "No learned guidance yet."));
            none.setTextColor(CrewTheme.TEXT_MUTED);
            root.addView(none);
        } else {
            for (int i = 0; i < rules.length(); i++) {
                final JSONObject rule = rules.optJSONObject(i);
                if (rule == null) continue;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(11), dp(9), dp(11), dp(9));
                row.setBackground(CrewTheme.createCard(
                        this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
                TextView ruleTitle = new TextView(this);
                ruleTitle.setText(rule.optString("title", I18n.get(this, "App 經驗", "App guidance")));
                ruleTitle.setTextSize(12);
                ruleTitle.setTypeface(Typeface.DEFAULT_BOLD);
                ruleTitle.setTextColor(CrewTheme.TEXT_PRIMARY);
                row.addView(ruleTitle);
                TextView guidance = new TextView(this);
                guidance.setText(rule.optString("guidance", ""));
                guidance.setTextSize(10.5f);
                guidance.setTextColor(CrewTheme.TEXT_SECONDARY);
                guidance.setPadding(0, dp(3), 0, dp(3));
                row.addView(guidance);
                TextView source = new TextView(this);
                source.setText(AppPlaybookStore.SOURCE_VOICE.equals(rule.optString("source", ""))
                        ? I18n.get(this, "語音學習", "Learned by voice")
                        : I18n.get(this, "手動建立", "Added manually"));
                source.setTextSize(9);
                source.setTextColor(CrewTheme.TEXT_MUTED);
                row.addView(source);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showRuleEditor(packageName, label, rule);
                    }
                });
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp(6));
                root.addView(row, rowLp);
            }
        }

        Button addRule = new Button(this);
        addRule.setAllCaps(false);
        addRule.setText(I18n.get(this, "＋ 新增一條經驗", "+ Add guidance"));
        addRule.setTextSize(11);
        addRule.setTextColor(CrewTheme.TEAL_300);
        addRule.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_TEAL, 10));
        addRule.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showRuleEditor(packageName, label, null);
            }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        addLp.setMargins(0, dp(8), 0, 0);
        root.addView(addRule, addLp);

        new AlertDialog.Builder(this)
                .setTitle(label.isEmpty() ? packageName : label)
                .setView(scroll)
                .setNegativeButton(I18n.get(this, "關閉", "Close"), null)
                .show();
    }

    private void showAppPicker() {
        final ArrayList<AppCatalog.Entry> apps = appCatalog.listLaunchable();
        Collections.sort(apps, new Comparator<AppCatalog.Entry>() {
            @Override public int compare(AppCatalog.Entry a, AppCatalog.Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        if (apps.isEmpty()) {
            Toast.makeText(this,
                    I18n.get(this, "找不到可啟動的 App", "No launchable apps found"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppCatalog.Entry app = apps.get(i);
            labels[i] = app.label + "\n" + app.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, "選擇 App", "Choose App"))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        AppCatalog.Entry app = apps.get(which);
                        showRuleEditor(app.packageName, app.label, null);
                    }
                })
                .setNegativeButton(I18n.get(this, "取消", "Cancel"), null)
                .show();
    }

    private void showAutonomyAppPicker() {
        final ArrayList<AppCatalog.Entry> apps =
                appCatalog.listLaunchable();
        Collections.sort(apps, new Comparator<AppCatalog.Entry>() {
            @Override public int compare(
                    AppCatalog.Entry a,
                    AppCatalog.Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        if (apps.isEmpty()) {
            Toast.makeText(
                    this,
                    I18n.get(
                            this,
                            "找不到可啟動的 App",
                            "No launchable apps found"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppCatalog.Entry app = apps.get(i);
            labels[i] =
                    (autonomyStore.isTrusted(app.packageName)
                            ? "✓ " : "")
                            + app.label
                            + "\n"
                            + app.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle(I18n.get(
                        this,
                        "選擇自主操作 App",
                        "Choose Trusted Autonomy App"))
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(
                            DialogInterface dialog,
                            int which) {
                        AppCatalog.Entry app = apps.get(which);
                        autonomyStore.setTrusted(
                                app.packageName, true);
                        render();
                        showProfile(app.packageName);
                    }
                })
                .setNegativeButton(
                        I18n.get(this, "取消", "Cancel"), null)
                .show();
    }

    private void updateAutonomyStateView(
            TextView view,
            boolean enabled) {
        if (view == null) return;
        view.setText(enabled ? "ON" : "OFF");
        view.setTextColor(
                enabled
                        ? CrewTheme.TEAL_300
                        : CrewTheme.TEXT_MUTED);
        view.setBackground(CrewTheme.createCard(
                this,
                enabled
                        ? Color.argb(45, 20, 184, 166)
                        : CrewTheme.BG_SURFACE,
                enabled
                        ? CrewTheme.BORDER_TEAL
                        : CrewTheme.BORDER_SUBTLE,
                12));
    }

    private void showRuleEditor(
            final String packageName,
            final String appLabel,
            final JSONObject existing) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(4));

        final EditText title = new EditText(this);
        title.setHint(I18n.get(this, "標題（例如：搜尋結果）", "Title (e.g. Search results)"));
        title.setText(existing == null ? "" : existing.optString("title", ""));
        title.setTextSize(12);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        title.setHintTextColor(CrewTheme.TEXT_MUTED);
        title.setSingleLine(true);
        title.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
        title.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(title);

        final EditText guidance = new EditText(this);
        guidance.setHint(I18n.get(this,
                "描述 Crew 在這個 App 應該記住的操作方式或陷阱",
                "Describe how Crew should operate this app or what to avoid"));
        guidance.setText(existing == null ? "" : existing.optString("guidance", ""));
        guidance.setTextSize(12);
        guidance.setTextColor(CrewTheme.TEXT_PRIMARY);
        guidance.setHintTextColor(CrewTheme.TEXT_MUTED);
        guidance.setGravity(Gravity.TOP | Gravity.START);
        guidance.setMinLines(4);
        guidance.setMaxLines(8);
        guidance.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        guidance.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE, CrewTheme.BORDER_SUBTLE, 10));
        guidance.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams guideLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        guideLp.setMargins(0, dp(8), 0, 0);
        root.addView(guidance, guideLp);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle((existing == null
                        ? I18n.get(this, "新增經驗 · ", "Add guidance · ")
                        : I18n.get(this, "編輯經驗 · ", "Edit guidance · "))
                        + appLabel)
                .setView(root)
                .setPositiveButton(I18n.get(this, "儲存", "Save"), null)
                .setNegativeButton(I18n.get(this, "取消", "Cancel"), null);
        if (existing != null) {
            builder.setNeutralButton(I18n.get(this, "刪除", "Delete"), null);
        }
        final AlertDialog dialog = builder.create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface unused) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                String g = guidance.getText().toString().trim();
                                if (g.isEmpty()) {
                                    guidance.setError(I18n.get(
                                            AppPlaybookActivity.this,
                                            "請輸入操作經驗",
                                            "Enter operational guidance"));
                                    return;
                                }
                                JSONObject result;
                                if (existing == null) {
                                    result = store.remember(
                                            packageName,
                                            appLabel,
                                            title.getText().toString(),
                                            g,
                                            AppPlaybookStore.SOURCE_MANUAL);
                                } else {
                                    result = store.update(
                                            packageName,
                                            existing.optString("id", ""),
                                            title.getText().toString(),
                                            g);
                                }
                                if (!result.optBoolean("success", false)) {
                                    Toast.makeText(
                                            AppPlaybookActivity.this,
                                            result.optString("error", "Save failed"),
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                dialog.dismiss();
                                render();
                            }
                        });

                if (existing != null) {
                    Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                    if (neutral != null) {
                        neutral.setTextColor(CrewTheme.ROSE_400);
                        neutral.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                new AlertDialog.Builder(AppPlaybookActivity.this)
                                        .setTitle(I18n.get(
                                                AppPlaybookActivity.this,
                                                "刪除這條 App 經驗？",
                                                "Delete this app guidance?"))
                                        .setPositiveButton(I18n.get(
                                                AppPlaybookActivity.this,
                                                "刪除", "Delete"),
                                                new DialogInterface.OnClickListener() {
                                                    @Override public void onClick(
                                                            DialogInterface d, int which) {
                                                        store.delete(
                                                                packageName,
                                                                existing.optString("id", ""));
                                                        dialog.dismiss();
                                                        render();
                                                    }
                                                })
                                        .setNegativeButton(I18n.get(
                                                AppPlaybookActivity.this,
                                                "取消", "Cancel"), null)
                                        .show();
                            }
                        });
                    }
                }
            }
        });
        dialog.show();
    }

    private TextView sectionLabel(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(10.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(CrewTheme.INDIGO_400);
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private TextView bodyText(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(10.5f);
        view.setTextColor(CrewTheme.TEXT_SECONDARY);
        view.setLineSpacing(dp(2), 1.08f);
        view.setPadding(dp(10), dp(9), dp(10), dp(9));
        return view;
    }
}
