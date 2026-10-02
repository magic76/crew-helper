package com.crewpocket.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
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
    private AppCapabilityStore capabilityStore;
    private AppCapabilityRegistry capabilityRegistry;
    private AppCapabilityDocumentImporter capabilityDocumentImporter;
    private AppAutonomyStore autonomyStore;
    private AppCatalog appCatalog;
    private LinearLayout content;
    private String incomingSharedLink = "";
    private boolean incomingShareHandled = false;

    private String extractSharedLinkFromIntent(
            Intent intent) {
        if (intent == null
                || !Intent.ACTION_SEND.equals(
                        intent.getAction())) {
            return "";
        }
        CharSequence extra =
                intent.getCharSequenceExtra(
                        Intent.EXTRA_TEXT);
        String text = extra == null
                ? ""
                : extra.toString().trim();
        if (text.isEmpty()) return "";

        java.util.regex.Matcher matcher =
                java.util.regex.Pattern
                        .compile(
                                "([A-Za-z][A-Za-z0-9+.-]{1,31}:[^\\s]+)")
                        .matcher(text);
        if (!matcher.find()) return "";

        String candidate = matcher.group(1);
        while (candidate.endsWith(".")
                || candidate.endsWith(",")
                || candidate.endsWith(")")
                || candidate.endsWith("]")) {
            candidate =
                    candidate.substring(
                            0,
                            candidate.length() - 1);
        }
        try {
            AppCapabilityTemplate.validateUri(
                    candidate);
            return candidate;
        } catch (Exception ignored) {
            return "";
        }
    }

    private int dp(float value) { return CrewTheme.dp(this, value); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AppPlaybookStore(this);
        capabilityStore = new AppCapabilityStore(this);
        capabilityRegistry =
                new AppCapabilityRegistry(this, capabilityStore);
        capabilityDocumentImporter =
                new AppCapabilityDocumentImporter(this);
        autonomyStore = new AppAutonomyStore(this);
        appCatalog = new AppCatalog(this);
        appCatalog.prewarm();
        AppCapabilitySync.maybeSync(this, capabilityStore);
        incomingSharedLink =
                extractSharedLinkFromIntent(getIntent());

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

        if (!incomingSharedLink.isEmpty()
                && !incomingShareHandled) {
            incomingShareHandled = true;
            content.post(new Runnable() {
                @Override public void run() {
                    showCapabilityAppPicker(
                            incomingSharedLink);
                }
            });
        }
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
                "Crew 對不同 App 的操作經驗與快速能力",
                "Crew's app-local guidance and fast capabilities"));
        subtitle.setTextSize(11);
        subtitle.setTextColor(CrewTheme.TEXT_SECONDARY);
        text.addView(subtitle);
        heading.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(heading);

        TextView explanation = new TextView(this);
        explanation.setText(I18n.get(this,
                "Crew 會優先使用你設定或遠端同步的 Deep Link / Android Intent；沒有能力時才回到 UI Agent。Capability 只描述低風險開啟/導向方式，不會增加付款、送出、帳號、刪除等敏感操作權限。",
                "Crew prefers configured or synced Deep Link / Android Intent capabilities, then falls back to the UI Agent. Capabilities only describe low-risk navigation/opening and never grant payment, send, account, deletion, or other sensitive authorization."));
        explanation.setTextSize(11);
        explanation.setTextColor(CrewTheme.TEXT_SECONDARY);
        explanation.setLineSpacing(dp(2), 1.08f);
        explanation.setPadding(0, dp(14), 0, dp(12));
        content.addView(explanation);

        TextView stats = new TextView(this);
        stats.setText(I18n.get(this,
                "經驗 " + store.learnedRuleCount() + " · 快速能力 "
                        + capabilityStore.capabilityCount() + " · 自主 "
                        + autonomyStore.trustedCount() + " 個",
                "Guidance " + store.learnedRuleCount() + " · capabilities "
                        + capabilityStore.capabilityCount() + " · "
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

        Button addCapability = new Button(this);
        addCapability.setAllCaps(false);
        addCapability.setText(I18n.get(
                this,
                "＋ 新增 App 快速能力",
                "+ Add App Capability"));
        addCapability.setTextSize(12);
        addCapability.setTypeface(Typeface.DEFAULT_BOLD);
        addCapability.setTextColor(CrewTheme.TEAL_300);
        addCapability.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_TEAL, 14));
        addCapability.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showCapabilityAppPicker();
            }
        });
        LinearLayout.LayoutParams capabilityLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(46));
        capabilityLp.setMargins(0, 0, 0, dp(7));
        content.addView(addCapability, capabilityLp);

        Button registry = new Button(this);
        registry.setAllCaps(false);
        registry.setText(I18n.get(
                this,
                "同步 Capability Registry",
                "Sync Capability Registry"));
        registry.setTextSize(11.5f);
        registry.setTextColor(CrewTheme.INDIGO_400);
        registry.setBackground(CrewTheme.createCard(
                this, CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE, 14));
        registry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showRemoteRegistryDialog();
            }
        });
        LinearLayout.LayoutParams registryLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(44));
        registryLp.setMargins(0, 0, 0, dp(7));
        content.addView(registry, registryLp);

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
        for (String pkg : capabilityStore.profilePackages()) {
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
        int capabilityCount =
                capabilityRegistry
                        .capabilitiesFor(packageName)
                        .length();
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
        if (capabilityCount > 0) {
            summary = capabilityCount
                    + I18n.get(this, " 個快速能力 · ", " capabilities · ")
                    + summary;
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

        TextView capabilityTitle = sectionLabel(I18n.get(
                this,
                "快速能力",
                "FAST CAPABILITIES"));
        capabilityTitle.setPadding(0, dp(12), 0, dp(6));
        root.addView(capabilityTitle);

        JSONArray capabilities =
                capabilityRegistry.capabilitiesFor(packageName);
        if (capabilities.length() == 0) {
            TextView none = bodyText(I18n.get(
                    this,
                    "目前沒有已知快速能力；Crew 會使用一般 UI Agent。",
                    "No deterministic capability is known; Crew will use the UI Agent."));
            none.setTextColor(CrewTheme.TEXT_MUTED);
            root.addView(none);
        } else {
            for (int i = 0; i < capabilities.length(); i++) {
                final JSONObject capability =
                        capabilities.optJSONObject(i);
                if (capability == null) continue;

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(11), dp(9), dp(11), dp(9));
                row.setBackground(CrewTheme.createCard(
                        this,
                        CrewTheme.BG_SURFACE,
                        CrewTheme.BORDER_SUBTLE,
                        10));

                TextView capName = new TextView(this);
                capName.setText(
                        capability.optString(
                                "label",
                                capability.optString("id", "")));
                capName.setTextSize(11.5f);
                capName.setTypeface(Typeface.DEFAULT_BOLD);
                capName.setTextColor(CrewTheme.TEXT_PRIMARY);
                row.addView(capName);

                JSONArray params =
                        capability.optJSONArray("params");
                String source =
                        capability.optString("source", "");
                StringBuilder meta = new StringBuilder();
                meta.append(source.isEmpty() ? "runtime" : source);
                if (params != null && params.length() > 0) {
                    meta.append(" · params: ");
                    for (int j = 0; j < params.length(); j++) {
                        if (j > 0) meta.append(", ");
                        meta.append(params.optString(j, ""));
                    }
                }
                TextView capMeta = new TextView(this);
                capMeta.setText(meta.toString());
                capMeta.setTextSize(9.5f);
                capMeta.setTextColor(CrewTheme.TEXT_MUTED);
                capMeta.setPadding(0, dp(3), 0, 0);
                row.addView(capMeta);

                if (AppCapabilityStore.SOURCE_LOCAL.equals(source)) {
                    row.setClickable(true);
                    row.setFocusable(true);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            showCapabilityEditor(
                                    packageName,
                                    label,
                                    capability);
                        }
                    });
                }

                LinearLayout.LayoutParams rowLp =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp(6));
                root.addView(row, rowLp);
            }
        }

        Button addCapabilityRule = new Button(this);
        addCapabilityRule.setAllCaps(false);
        addCapabilityRule.setText(I18n.get(
                this,
                "＋ 新增快速能力",
                "+ Add Fast Capability"));
        addCapabilityRule.setTextSize(11);
        addCapabilityRule.setTextColor(CrewTheme.TEAL_300);
        addCapabilityRule.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_ELEVATED,
                CrewTheme.BORDER_TEAL,
                10));
        addCapabilityRule.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showCapabilityWizard(
                        packageName,
                        label,
                        "");
            }
        });
        LinearLayout.LayoutParams addCapabilityRuleLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(44));
        addCapabilityRuleLp.setMargins(0, dp(6), 0, 0);
        root.addView(addCapabilityRule, addCapabilityRuleLp);

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

    private void showCapabilityAppPicker() {
        showCapabilityAppPicker("");
    }

    private void showCapabilityAppPicker(
            final String prefillLink) {
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
            labels[i] = app.label + "\n" + app.packageName;
        }

        new AlertDialog.Builder(this)
                .setTitle(
                        prefillLink == null
                                || prefillLink.trim().isEmpty()
                                ? I18n.get(
                                        this,
                                        "要讓哪個 App 更快？",
                                        "Which App should Crew speed up?")
                                : I18n.get(
                                        this,
                                        "這個連結要交給哪個 App？",
                                        "Which App should open this link?"))
                .setItems(
                        labels,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface dialog,
                                    int which) {
                                AppCatalog.Entry app =
                                        apps.get(which);
                                showCapabilityWizard(
                                        app.packageName,
                                        app.label,
                                        prefillLink == null
                                                ? ""
                                                : prefillLink.trim());
                            }
                        })
                .setNegativeButton(
                        I18n.get(this, "取消", "Cancel"),
                        null)
                .show();
    }

    private void showCapabilityWizard(
            final String packageName,
            final String appLabel,
            final String prefillLink) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(6));
        scroll.addView(root);

        TextView intro = bodyText(I18n.get(
                this,
                "你不需要知道 Deep Link 格式。Crew 可以先找已知能力、讀官方文件，或測試你手上的實際連結。",
                "You do not need to know Deep Link syntax. Crew can check known capabilities, read official docs, or test a real link you already have."));
        intro.setPadding(0, 0, 0, dp(10));
        root.addView(intro);

        final TextView knownStatus = bodyText("");
        knownStatus.setTextColor(CrewTheme.TEAL_300);
        knownStatus.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        knownStatus.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(knownStatus);
        refreshKnownCapabilities(packageName, knownStatus);

        Button findKnown = new Button(this);
        findKnown.setAllCaps(false);
        findKnown.setText(I18n.get(
                this,
                "重新尋找可用能力",
                "Find available capabilities"));
        findKnown.setTextSize(11);
        findKnown.setTextColor(CrewTheme.INDIGO_400);
        findKnown.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_ELEVATED,
                CrewTheme.BORDER_SUBTLE,
                10));
        LinearLayout.LayoutParams findLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(42));
        findLp.setMargins(0, dp(7), 0, dp(12));
        root.addView(findKnown, findLp);

        TextView docsTitle = sectionLabel(I18n.get(
                this,
                "有官方文件？",
                "HAVE OFFICIAL DOCS?"));
        root.addView(docsTitle);

        final EditText docsUrl = new EditText(this);
        docsUrl.setHint(I18n.get(
                this,
                "貼上官方文件網址",
                "Paste official documentation URL"));
        docsUrl.setTextSize(11.5f);
        docsUrl.setTextColor(CrewTheme.TEXT_PRIMARY);
        docsUrl.setHintTextColor(CrewTheme.TEXT_MUTED);
        docsUrl.setSingleLine(true);
        docsUrl.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI);
        docsUrl.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        docsUrl.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(docsUrl);

        final TextView docsStatus = bodyText(I18n.get(
                this,
                "Crew 只會抽取文件明確寫出的低風險 App Link / URI / Intent，不會照文件裡的其他指令執行。",
                "Crew only extracts explicitly documented low-risk App Links / URIs / Intents and never follows other instructions from the page."));
        docsStatus.setTextColor(CrewTheme.TEXT_MUTED);
        docsStatus.setPadding(0, dp(6), 0, 0);
        root.addView(docsStatus);

        Button analyzeDocs = new Button(this);
        analyzeDocs.setAllCaps(false);
        analyzeDocs.setText(I18n.get(
                this,
                "從官方文件尋找能力",
                "Find capabilities from docs"));
        analyzeDocs.setTextSize(11);
        analyzeDocs.setTextColor(CrewTheme.TEAL_300);
        analyzeDocs.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_ELEVATED,
                CrewTheme.BORDER_TEAL,
                10));
        LinearLayout.LayoutParams docsLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(42));
        docsLp.setMargins(0, dp(7), 0, dp(14));
        root.addView(analyzeDocs, docsLp);

        TextView linkTitle = sectionLabel(I18n.get(
                this,
                "我有一個連結",
                "I HAVE A LINK"));
        root.addView(linkTitle);

        final EditText firstLink = new EditText(this);
        firstLink.setHint(I18n.get(
                this,
                "貼上 App Link / Deep Link",
                "Paste an App Link / Deep Link"));
        firstLink.setText(prefillLink == null ? "" : prefillLink);
        firstLink.setTextSize(11.5f);
        firstLink.setTextColor(CrewTheme.TEXT_PRIMARY);
        firstLink.setHintTextColor(CrewTheme.TEXT_MUTED);
        firstLink.setSingleLine(false);
        firstLink.setMinLines(2);
        firstLink.setMaxLines(4);
        firstLink.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        firstLink.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        firstLink.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(firstLink);

        final TextView testStatus = bodyText(I18n.get(
                this,
                "Crew 會先確認目前安裝的 App 是否真的能接這個連結。",
                "Crew first checks whether the installed App can really handle this link."));
        testStatus.setTextColor(CrewTheme.TEXT_MUTED);
        testStatus.setPadding(0, dp(6), 0, 0);
        root.addView(testStatus);

        Button testLink = new Button(this);
        testLink.setAllCaps(false);
        testLink.setText(I18n.get(
                this,
                "測試開啟",
                "Test opening"));
        testLink.setTextSize(11);
        testLink.setTextColor(CrewTheme.TEAL_300);
        testLink.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_ELEVATED,
                CrewTheme.BORDER_TEAL,
                10));
        LinearLayout.LayoutParams testLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(42));
        testLp.setMargins(0, dp(7), 0, dp(7));
        root.addView(testLink, testLp);

        Button addExample = new Button(this);
        addExample.setAllCaps(false);
        addExample.setText(I18n.get(
                this,
                "＋ 加第二個範例，讓 Crew 自動找變數",
                "+ Add a second example so Crew can infer the variable"));
        addExample.setTextSize(10.5f);
        addExample.setTextColor(CrewTheme.INDIGO_400);
        addExample.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        root.addView(addExample, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)));

        final LinearLayout exampleArea = new LinearLayout(this);
        exampleArea.setOrientation(LinearLayout.VERTICAL);
        exampleArea.setVisibility(View.GONE);
        exampleArea.setPadding(0, dp(8), 0, 0);

        final EditText secondLink = new EditText(this);
        secondLink.setHint(I18n.get(
                this,
                "貼另一個同類型連結",
                "Paste another link of the same type"));
        secondLink.setTextSize(11.5f);
        secondLink.setTextColor(CrewTheme.TEXT_PRIMARY);
        secondLink.setHintTextColor(CrewTheme.TEXT_MUTED);
        secondLink.setSingleLine(false);
        secondLink.setMinLines(2);
        secondLink.setMaxLines(4);
        secondLink.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        secondLink.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        secondLink.setPadding(dp(11), dp(9), dp(11), dp(9));
        exampleArea.addView(secondLink);

        final TextView inference = bodyText("");
        inference.setTextColor(CrewTheme.TEAL_300);
        inference.setPadding(0, dp(7), 0, 0);
        exampleArea.addView(inference);

        Button analyze = new Button(this);
        analyze.setAllCaps(false);
        analyze.setText(I18n.get(
                this,
                "分析兩個範例",
                "Analyze examples"));
        analyze.setTextSize(10.5f);
        analyze.setTextColor(CrewTheme.TEAL_300);
        analyze.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_ELEVATED,
                CrewTheme.BORDER_TEAL,
                10));
        LinearLayout.LayoutParams analyzeLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(40));
        analyzeLp.setMargins(0, dp(7), 0, 0);
        exampleArea.addView(analyze, analyzeLp);
        root.addView(exampleArea);

        final AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(
                                this,
                                "新增快速能力 · ",
                                "Add Fast Capability · ")
                                + appLabel)
                        .setView(scroll)
                        .setPositiveButton(
                                I18n.get(
                                        this,
                                        "儲存",
                                        "Save"),
                                null)
                        .setNeutralButton(
                                I18n.get(
                                        this,
                                        "進階設定",
                                        "Advanced"),
                                null)
                        .setNegativeButton(
                                I18n.get(
                                        this,
                                        "取消",
                                        "Cancel"),
                                null)
                        .create();

        findKnown.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                knownStatus.setText(I18n.get(
                        AppPlaybookActivity.this,
                        "正在重新尋找…",
                        "Checking again…"));
                if (!capabilityStore.remoteUrl().isEmpty()) {
                    AppCapabilitySync.sync(
                            AppPlaybookActivity.this,
                            capabilityStore,
                            new AppCapabilitySync.Callback() {
                                @Override public void onComplete(
                                        JSONObject result) {
                                    refreshKnownCapabilities(
                                            packageName,
                                            knownStatus);
                                }
                            });
                } else {
                    refreshKnownCapabilities(
                            packageName,
                            knownStatus);
                }
            }
        });

        analyzeDocs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String url =
                        docsUrl.getText()
                                .toString()
                                .trim();
                if (url.isEmpty()) {
                    docsUrl.setError(I18n.get(
                            AppPlaybookActivity.this,
                            "先貼官方文件網址",
                            "Paste a documentation URL first"));
                    return;
                }

                analyzeDocs.setEnabled(false);
                docsStatus.setTextColor(CrewTheme.INDIGO_400);
                docsStatus.setText(I18n.get(
                        AppPlaybookActivity.this,
                        "正在讀取文件並整理可測試的快速能力…",
                        "Reading docs and extracting testable capabilities…"));

                capabilityDocumentImporter.importAsync(
                        packageName,
                        appLabel,
                        url,
                        new AppCapabilityDocumentImporter.Callback() {
                            @Override public void onComplete(
                                    JSONObject result) {
                                analyzeDocs.setEnabled(true);
                                if (!result.optBoolean(
                                        "success",
                                        false)) {
                                    docsStatus.setTextColor(
                                            CrewTheme.ROSE_400);
                                    docsStatus.setText(
                                            documentImportErrorMessage(
                                                    result.optString(
                                                            "error",
                                                            "DOCUMENT_ANALYSIS_FAILED")));
                                    return;
                                }

                                JSONArray found =
                                        result.optJSONArray(
                                                "capabilities");
                                int count =
                                        found == null
                                                ? 0
                                                : found.length();
                                if (count <= 0) {
                                    docsStatus.setTextColor(
                                            CrewTheme.TEXT_MUTED);
                                    docsStatus.setText(I18n.get(
                                            AppPlaybookActivity.this,
                                            "這份文件沒有找到可安全匯入的外部連結能力。可以改貼 App 分享出來的實際連結。",
                                            "No safely importable external-link capability was found. Try a real link shared from the App instead."));
                                    return;
                                }

                                docsStatus.setTextColor(
                                        CrewTheme.TEAL_300);
                                docsStatus.setText(
                                        I18n.get(
                                                AppPlaybookActivity.this,
                                                "✓ 找到 ",
                                                "✓ Found ")
                                                + count
                                                + I18n.get(
                                                        AppPlaybookActivity.this,
                                                        " 個候選，請逐項測試後加入",
                                                        " candidates; test each before adding"));
                                showDocumentCapabilityCandidates(
                                        packageName,
                                        appLabel,
                                        result);
                            }
                        });
            }
        });

        addExample.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                exampleArea.setVisibility(
                        exampleArea.getVisibility() == View.VISIBLE
                                ? View.GONE
                                : View.VISIBLE);
            }
        });

        analyze.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    AppCapabilityAssistant.Suggestion suggestion =
                            AppCapabilityAssistant.fromExamples(
                                    firstLink.getText()
                                            .toString(),
                                    secondLink.getText()
                                            .toString());
                    inference.setText(
                            I18n.get(
                                    AppPlaybookActivity.this,
                                    "Crew 找到可替換部分：\n",
                                    "Crew found a reusable variable:\n")
                                    + suggestion.label
                                    + "\n"
                                    + suggestion.template);
                } catch (Exception error) {
                    inference.setTextColor(CrewTheme.ROSE_400);
                    inference.setText(I18n.get(
                            AppPlaybookActivity.this,
                            "這兩個範例差異太大，暫時無法安全推導模板。",
                            "These examples differ too much to infer a safe template."));
                }
            }
        });

        testLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String link =
                        firstLink.getText()
                                .toString()
                                .trim();
                if (link.isEmpty()) {
                    firstLink.setError(I18n.get(
                            AppPlaybookActivity.this,
                            "先貼一個連結",
                            "Paste a link first"));
                    return;
                }

                JSONObject inspected =
                        AppCapabilityLinkTester.inspect(
                                AppPlaybookActivity.this,
                                packageName,
                                link);
                if (!inspected.optBoolean(
                        "success",
                        false)) {
                    testStatus.setTextColor(
                            CrewTheme.ROSE_400);
                    testStatus.setText(I18n.get(
                            AppPlaybookActivity.this,
                            "目前安裝版本無法用這個連結開啟 "
                                    + appLabel,
                            "The installed version cannot open this link in "
                                    + appLabel));
                    return;
                }

                testStatus.setTextColor(
                        CrewTheme.TEAL_300);
                testStatus.setText(I18n.get(
                        AppPlaybookActivity.this,
                        "✓ 可以交給 "
                                + appLabel
                                + "。現在會實際開啟一次測試；返回 Crew 後可直接儲存。",
                        "✓ "
                                + appLabel
                                + " can handle it. Crew will open it once for testing; return here to save."));
                AppCapabilityLinkTester.launch(
                        AppPlaybookActivity.this,
                        packageName,
                        link);
            }
        });

        dialog.setOnShowListener(
                new DialogInterface.OnShowListener() {
                    @Override public void onShow(
                            DialogInterface unused) {
                        dialog.getButton(
                                AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        new View.OnClickListener() {
                                            @Override public void onClick(
                                                    View v) {
                                                String first =
                                                        firstLink.getText()
                                                                .toString()
                                                                .trim();
                                                if (first.isEmpty()) {
                                                    firstLink.setError(
                                                            I18n.get(
                                                                    AppPlaybookActivity.this,
                                                                    "先貼一個連結",
                                                                    "Paste a link first"));
                                                    return;
                                                }

                                                JSONObject inspected =
                                                        AppCapabilityLinkTester.inspect(
                                                                AppPlaybookActivity.this,
                                                                packageName,
                                                                first);
                                                if (!inspected.optBoolean(
                                                        "success",
                                                        false)) {
                                                    testStatus.setTextColor(
                                                            CrewTheme.ROSE_400);
                                                    testStatus.setText(
                                                            I18n.get(
                                                                    AppPlaybookActivity.this,
                                                                    "這個連結目前無法由目標 App 處理，先不要儲存。",
                                                                    "The target App cannot currently handle this link, so Crew will not save it."));
                                                    return;
                                                }

                                                try {
                                                    String second =
                                                            secondLink.getText()
                                                                    .toString()
                                                                    .trim();
                                                    AppCapabilityAssistant.Suggestion suggestion =
                                                            second.isEmpty()
                                                                    ? AppCapabilityAssistant
                                                                            .fromSingle(
                                                                                    first)
                                                                    : AppCapabilityAssistant
                                                                            .fromExamples(
                                                                                    first,
                                                                                    second);

                                                    JSONObject saved =
                                                            capabilityStore
                                                                    .saveLocal(
                                                                            packageName,
                                                                            appLabel,
                                                                            suggestion.capabilityId,
                                                                            suggestion.label,
                                                                            "URI",
                                                                            suggestion.template,
                                                                            "",
                                                                            "");
                                                    if (!saved.optBoolean(
                                                            "success",
                                                            false)) {
                                                        Toast.makeText(
                                                                AppPlaybookActivity.this,
                                                                saved.optString(
                                                                        "error",
                                                                        "SAVE_FAILED"),
                                                                Toast.LENGTH_SHORT)
                                                                .show();
                                                        return;
                                                    }

                                                    dialog.dismiss();
                                                    render();
                                                    Toast.makeText(
                                                            AppPlaybookActivity.this,
                                                            suggestion.reusable
                                                                    ? I18n.get(
                                                                            AppPlaybookActivity.this,
                                                                            "已建立可重用快速能力："
                                                                                    + suggestion.label,
                                                                            "Reusable capability saved: "
                                                                                    + suggestion.label)
                                                                    : I18n.get(
                                                                            AppPlaybookActivity.this,
                                                                            "已記住這個快速連結",
                                                                            "Fast link saved"),
                                                            Toast.LENGTH_LONG)
                                                            .show();
                                                } catch (Exception error) {
                                                    inference.setTextColor(
                                                            CrewTheme.ROSE_400);
                                                    inference.setText(
                                                            I18n.get(
                                                                    AppPlaybookActivity.this,
                                                                    "範例無法安全推導。可以移除第二個範例直接儲存固定連結，或使用進階設定。",
                                                                    "Crew could not safely infer a template. Remove the second example to save the exact link, or use Advanced settings."));
                                                }
                                            }
                                        });

                        dialog.getButton(
                                AlertDialog.BUTTON_NEUTRAL)
                                .setOnClickListener(
                                        new View.OnClickListener() {
                                            @Override public void onClick(
                                                    View v) {
                                                String link =
                                                        firstLink.getText()
                                                                .toString()
                                                                .trim();
                                                dialog.dismiss();
                                                showCapabilityEditor(
                                                        packageName,
                                                        appLabel,
                                                        null,
                                                        link);
                                            }
                                        });
                    }
                });

        dialog.show();
    }

    private void refreshKnownCapabilities(
            String packageName,
            TextView status) {
        if (status == null) return;
        JSONArray capabilities =
                capabilityRegistry
                        .capabilitiesFor(packageName);
        if (capabilities.length() == 0) {
            status.setTextColor(CrewTheme.TEXT_MUTED);
            status.setText(I18n.get(
                    this,
                    "沒有找到已知快速能力。可以貼一個連結讓 Crew 測試。",
                    "No known fast capability found. Paste a link for Crew to test."));
            return;
        }

        StringBuilder out = new StringBuilder();
        out.append(I18n.get(
                this,
                "✓ 已找到 ",
                "✓ Found "))
                .append(capabilities.length())
                .append(I18n.get(
                        this,
                        " 個可直接使用的能力",
                        " capabilities ready to use"));
        int max = Math.min(5, capabilities.length());
        for (int i = 0; i < max; i++) {
            JSONObject item =
                    capabilities.optJSONObject(i);
            if (item == null) continue;
            out.append("\n• ")
                    .append(
                            item.optString(
                                    "label",
                                    item.optString("id", "")));
        }
        if (capabilities.length() > max) {
            out.append("\n… +")
                    .append(capabilities.length() - max);
        }
        status.setTextColor(CrewTheme.TEAL_300);
        status.setText(out.toString());
    }

    private void showRemoteRegistryDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(4));

        TextView help = bodyText(I18n.get(
                this,
                "填入 HTTPS JSON manifest。Crew 最多每 24 小時自動同步一次；本機自訂 capability 永遠優先於遠端同名項目。Registry 只載入宣告資料，不下載或執行程式碼。",
                "Enter an HTTPS JSON manifest. Crew auto-syncs at most once every 24 hours; local capabilities always override remote entries with the same id. Registry data is declarative only and never downloads executable code."));
        help.setPadding(0, 0, 0, dp(8));
        root.addView(help);

        final EditText url = new EditText(this);
        url.setHint("https://…/crew-app-capabilities.json");
        url.setText(capabilityStore.remoteUrl());
        url.setTextSize(11.5f);
        url.setSingleLine(true);
        url.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI);
        url.setTextColor(CrewTheme.TEXT_PRIMARY);
        url.setHintTextColor(CrewTheme.TEXT_MUTED);
        url.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        url.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(url);

        String revision = capabilityStore.remoteRevision();
        if (!revision.isEmpty()) {
            TextView current = bodyText(
                    I18n.get(this, "目前 revision: ", "Current revision: ")
                            + revision);
            current.setTextColor(CrewTheme.TEXT_MUTED);
            current.setPadding(0, dp(7), 0, 0);
            root.addView(current);
        }

        final AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(
                                this,
                                "Capability Registry",
                                "Capability Registry"))
                        .setView(root)
                        .setPositiveButton(
                                I18n.get(
                                        this,
                                        "儲存並同步",
                                        "Save & Sync"),
                                null)
                        .setNeutralButton(
                                I18n.get(
                                        this,
                                        "清除",
                                        "Clear"),
                                null)
                        .setNegativeButton(
                                I18n.get(this, "取消", "Cancel"),
                                null)
                        .create();

        dialog.setOnShowListener(
                new DialogInterface.OnShowListener() {
                    @Override public void onShow(
                            DialogInterface unused) {
                        dialog.getButton(
                                AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        new View.OnClickListener() {
                                            @Override public void onClick(
                                                    View v) {
                                                String value =
                                                        url.getText()
                                                                .toString()
                                                                .trim();
                                                capabilityStore
                                                        .setRemoteUrl(
                                                                value);
                                                if (value.isEmpty()) {
                                                    dialog.dismiss();
                                                    render();
                                                    return;
                                                }

                                                Toast.makeText(
                                                        AppPlaybookActivity.this,
                                                        I18n.get(
                                                                AppPlaybookActivity.this,
                                                                "正在同步 Registry",
                                                                "Syncing Registry"),
                                                        Toast.LENGTH_SHORT)
                                                        .show();
                                                AppCapabilitySync.sync(
                                                        AppPlaybookActivity.this,
                                                        capabilityStore,
                                                        new AppCapabilitySync.Callback() {
                                                            @Override
                                                            public void onComplete(
                                                                    JSONObject result) {
                                                                boolean ok =
                                                                        result.optBoolean(
                                                                                "success",
                                                                                false);
                                                                Toast.makeText(
                                                                        AppPlaybookActivity.this,
                                                                        ok
                                                                                ? I18n.get(
                                                                                        AppPlaybookActivity.this,
                                                                                        "同步完成："
                                                                                                + result.optInt("apps", 0)
                                                                                                + " Apps · "
                                                                                                + result.optInt("capabilities", 0)
                                                                                                + " abilities",
                                                                                        "Synced "
                                                                                                + result.optInt("apps", 0)
                                                                                                + " apps · "
                                                                                                + result.optInt("capabilities", 0)
                                                                                                + " capabilities")
                                                                                : result.optString(
                                                                                        "error",
                                                                                        "SYNC_FAILED"),
                                                                        Toast.LENGTH_LONG)
                                                                        .show();
                                                                render();
                                                            }
                                                        });
                                                dialog.dismiss();
                                            }
                                        });

                        dialog.getButton(
                                AlertDialog.BUTTON_NEUTRAL)
                                .setOnClickListener(
                                        new View.OnClickListener() {
                                            @Override public void onClick(
                                                    View v) {
                                                capabilityStore
                                                        .setRemoteUrl("");
                                                dialog.dismiss();
                                                render();
                                            }
                                        });
                    }
                });
        dialog.show();
    }

    private void showCapabilityEditor(
            final String packageName,
            final String appLabel,
            final JSONObject existing) {
        showCapabilityEditor(
                packageName,
                appLabel,
                existing,
                "");
    }

    private void showCapabilityEditor(
            final String packageName,
            final String appLabel,
            final JSONObject existing,
            final String prefillLink) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(4));

        TextView packageView = bodyText(packageName);
        packageView.setTextColor(CrewTheme.TEXT_MUTED);
        packageView.setPadding(0, 0, 0, dp(8));
        root.addView(packageView);

        AppCapabilityAssistant.Suggestion advancedSuggestion = null;
        if (existing == null
                && prefillLink != null
                && !prefillLink.trim().isEmpty()) {
            try {
                advancedSuggestion =
                        AppCapabilityAssistant.fromSingle(
                                prefillLink);
            } catch (Exception ignored) {}
        }
        final AppCapabilityAssistant.Suggestion inferredAdvanced =
                advancedSuggestion;

        final EditText id = new EditText(this);
        id.setHint("OPEN_PRODUCT");
        id.setText(
                existing == null
                        ? (inferredAdvanced == null
                                ? ""
                                : inferredAdvanced.capabilityId)
                        : existing.optString("id", ""));
        id.setEnabled(existing == null);
        id.setSingleLine(true);
        id.setTextSize(12);
        id.setTextColor(CrewTheme.TEXT_PRIMARY);
        id.setHintTextColor(CrewTheme.TEXT_MUTED);
        id.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        id.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(id);

        final EditText label = new EditText(this);
        label.setHint(I18n.get(
                this,
                "顯示名稱，例如：開啟商品",
                "Label, e.g. Open product"));
        label.setText(
                existing == null
                        ? (inferredAdvanced == null
                                ? ""
                                : inferredAdvanced.label)
                        : existing.optString("label", ""));
        label.setSingleLine(true);
        label.setTextSize(12);
        label.setTextColor(CrewTheme.TEXT_PRIMARY);
        label.setHintTextColor(CrewTheme.TEXT_MUTED);
        label.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        label.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams fieldLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldLp.setMargins(0, dp(8), 0, 0);
        root.addView(label, fieldLp);

        final EditText uri = new EditText(this);
        uri.setHint("myapp://product/{id}");
        uri.setText(
                existing == null
                        ? (inferredAdvanced == null
                                ? ""
                                : inferredAdvanced.template)
                        : existing.optString(
                                "uriTemplate", ""));
        uri.setTextSize(11.5f);
        uri.setTextColor(CrewTheme.TEXT_PRIMARY);
        uri.setHintTextColor(CrewTheme.TEXT_MUTED);
        uri.setSingleLine(false);
        uri.setMinLines(2);
        uri.setMaxLines(4);
        uri.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        uri.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        uri.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams uriLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        uriLp.setMargins(0, dp(8), 0, 0);
        root.addView(uri, uriLp);

        final EditText action = new EditText(this);
        action.setHint(I18n.get(
                this,
                "Intent action（選填；預設 ACTION_VIEW）",
                "Intent action (optional; defaults to ACTION_VIEW)"));
        action.setText(
                existing == null
                        ? ""
                        : existing.optString("action", ""));
        action.setSingleLine(true);
        action.setTextSize(10.5f);
        action.setTextColor(CrewTheme.TEXT_SECONDARY);
        action.setHintTextColor(CrewTheme.TEXT_MUTED);
        action.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                10));
        action.setPadding(dp(11), dp(9), dp(11), dp(9));
        LinearLayout.LayoutParams actionLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.setMargins(0, dp(8), 0, 0);
        root.addView(action, actionLp);

        TextView note = bodyText(I18n.get(
                this,
                "參數用 {name} 宣告，例如 spotify:album:{albumId}。Capability 只允許低風險 VIEW / SENDTO / DIAL；Runtime 執行前仍會 resolveActivity，執行後仍會驗證 App/畫面。",
                "Declare parameters as {name}, e.g. spotify:album:{albumId}. Capabilities are limited to low-risk VIEW / SENDTO / DIAL; Runtime still resolves before launch and verifies the App/screen afterward."));
        note.setTextColor(CrewTheme.TEXT_MUTED);
        note.setPadding(0, dp(8), 0, 0);
        root.addView(note);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle(
                                (existing == null
                                        ? I18n.get(
                                                this,
                                                "新增快速能力 · ",
                                                "Add capability · ")
                                        : I18n.get(
                                                this,
                                                "編輯快速能力 · ",
                                                "Edit capability · "))
                                        + appLabel)
                        .setView(root)
                        .setPositiveButton(
                                I18n.get(this, "儲存", "Save"),
                                null)
                        .setNegativeButton(
                                I18n.get(this, "取消", "Cancel"),
                                null);
        if (existing != null) {
            builder.setNeutralButton(
                    I18n.get(this, "刪除", "Delete"),
                    null);
        }

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(
                new DialogInterface.OnShowListener() {
                    @Override public void onShow(
                            DialogInterface unused) {
                        dialog.getButton(
                                AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        new View.OnClickListener() {
                                            @Override public void onClick(
                                                    View v) {
                                                String actionValue =
                                                        action.getText()
                                                                .toString()
                                                                .trim();
                                                String kind =
                                                        actionValue.isEmpty()
                                                                || android.content.Intent.ACTION_VIEW.equals(
                                                                        actionValue)
                                                                ? "URI"
                                                                : "INTENT";
                                                JSONObject saved =
                                                        capabilityStore.saveLocal(
                                                                packageName,
                                                                appLabel,
                                                                id.getText()
                                                                        .toString(),
                                                                label.getText()
                                                                        .toString(),
                                                                kind,
                                                                uri.getText()
                                                                        .toString(),
                                                                actionValue,
                                                                "");
                                                if (!saved.optBoolean(
                                                        "success",
                                                        false)) {
                                                    Toast.makeText(
                                                            AppPlaybookActivity.this,
                                                            saved.optString(
                                                                    "error",
                                                                    "SAVE_FAILED"),
                                                            Toast.LENGTH_SHORT)
                                                            .show();
                                                    return;
                                                }
                                                dialog.dismiss();
                                                render();
                                                Toast.makeText(
                                                        AppPlaybookActivity.this,
                                                        I18n.get(
                                                                AppPlaybookActivity.this,
                                                                "已儲存快速能力",
                                                                "Capability saved"),
                                                        Toast.LENGTH_SHORT)
                                                        .show();
                                            }
                                        });

                        if (existing != null) {
                            Button delete =
                                    dialog.getButton(
                                            AlertDialog.BUTTON_NEUTRAL);
                            delete.setTextColor(
                                    CrewTheme.ROSE_400);
                            delete.setOnClickListener(
                                    new View.OnClickListener() {
                                        @Override public void onClick(
                                                View v) {
                                            boolean removed =
                                                    capabilityStore.deleteLocal(
                                                            packageName,
                                                            existing.optString(
                                                                    "id",
                                                                    ""));
                                            if (!removed) {
                                                Toast.makeText(
                                                        AppPlaybookActivity.this,
                                                        "CAPABILITY_NOT_FOUND",
                                                        Toast.LENGTH_SHORT)
                                                        .show();
                                                return;
                                            }
                                            dialog.dismiss();
                                            render();
                                        }
                                    });
                        }
                    }
                });
        dialog.show();
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
