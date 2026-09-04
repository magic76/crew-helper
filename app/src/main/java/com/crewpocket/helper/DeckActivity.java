package com.crewpocket.helper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.ref.WeakReference;

/** Full-screen, offline-safe Deck surface. Data drives templates; no WebView is needed for normal cards. */
public class DeckActivity extends Activity {
    private static final int REQUEST_IMPORT_DECK = 741;
    private static WeakReference<DeckActivity> visible = new WeakReference<DeckActivity>(null);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout cardHost;
    private TextView position;
    private TextView titleBar;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        DeckRepository.initialize(this);
        DeckRepository.ensureActiveDeck();
        getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
        getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        getWindow().getDecorView().setBackgroundColor(CrewTheme.BG_PRIMARY);
        visible = new WeakReference<DeckActivity>(this);

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(CrewTheme.BG_PRIMARY);
        root.setPadding(dp(18), dp(20), dp(18), dp(16)); setContentView(root);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setOrientation(LinearLayout.HORIZONTAL);
        TextView close = button("‹", CrewTheme.BG_ELEVATED); close.setTextSize(30); close.setContentDescription("關閉簡報");
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        titleBar = text("Live Deck", 16, CrewTheme.TEXT_PRIMARY, true); titleBar.setPadding(dp(10), 0, 0, 0);
        header.addView(titleBar, new LinearLayout.LayoutParams(0, dp(48), 1));
        position = text("—", 12, CrewTheme.TEAL_300, true); position.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        header.addView(position, new LinearLayout.LayoutParams(dp(70), dp(48)));
        TextView importDeck = button("＋", CrewTheme.BG_ELEVATED); importDeck.setTextSize(23); importDeck.setContentDescription("匯入 Deck 資料夾");
        importDeck.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseDeckFolder(); } });
        header.addView(importDeck, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        cardHost = new LinearLayout(this); cardHost.setOrientation(LinearLayout.VERTICAL); cardHost.setPadding(0, dp(16), 0, dp(12));
        scroll.addView(cardHost); root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER); nav.setPadding(0, dp(8), 0, 0);
        TextView previous = button("‹", CrewTheme.BG_ELEVATED); previous.setContentDescription("上一張");
        previous.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { DeckRepository.userNavigate(-1); } });
        TextView next = button("›", CrewTheme.INDIGO_600); next.setContentDescription("下一張");
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { DeckRepository.userNavigate(1); } });
        nav.addView(previous, new LinearLayout.LayoutParams(dp(64), dp(48))); LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(dp(64), dp(48)); nextLp.setMargins(dp(18), 0, 0, 0); nav.addView(next, nextLp); root.addView(nav);
        renderCurrent();
    }

    @Override protected void onResume() { super.onResume(); visible = new WeakReference<DeckActivity>(this); renderCurrent(); }
    @Override protected void onDestroy() { if (visible.get() == this) visible = new WeakReference<DeckActivity>(null); super.onDestroy(); }

    private void chooseDeckFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMPORT_DECK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_DECK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        Toast.makeText(this, "正在匯入 Deck…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { @Override public void run() {
            final JSONObject result = DeckRepository.importDeckTree(DeckActivity.this, uri);
            ui.post(new Runnable() { @Override public void run() {
                if (result.optBoolean("success")) {
                    Toast.makeText(DeckActivity.this, "已匯入：「" + result.optString("title") + "」", Toast.LENGTH_LONG).show();
                    DeckRepository.openDeck(result.optString("deckId")); renderCurrent();
                } else Toast.makeText(DeckActivity.this, result.optString("error", "匯入失敗"), Toast.LENGTH_LONG).show();
            }});
        }}, "crew-deck-import").start();
    }

    static void showActiveCard() {
        final DeckActivity activity = visible.get();
        if (activity != null) activity.ui.post(new Runnable() { @Override public void run() { activity.renderCurrent(); } });
    }

    private void renderCurrent() {
        if (cardHost == null) return;
        DeckRepository.Deck deck = DeckRepository.activeDeck(); int index = DeckRepository.activeIndex();
        cardHost.removeAllViews();
        if (deck == null || deck.cards.length() == 0) { cardHost.addView(text("尚未載入簡報", 18, CrewTheme.TEXT_SECONDARY, true)); return; }
        JSONObject card = deck.cards.optJSONObject(index); if (card == null) return;
        titleBar.setText(deck.title); position.setText((index + 1) + " / " + deck.cards.length());
        String type = card.optString("type", "content");
        LinearLayout surface = new LinearLayout(this); surface.setOrientation(LinearLayout.VERTICAL); surface.setPadding(dp(22), dp(26), dp(22), dp(24));
        int accent = "cover".equals(type) ? CrewTheme.INDIGO_600 : CrewTheme.BG_SURFACE;
        surface.setBackground(CrewTheme.createCard(this, accent, "cover".equals(type) ? CrewTheme.INDIGO_400 : CrewTheme.BORDER_SUBTLE, 24));
        cardHost.addView(surface, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addImageIfPresent(surface, deck, card.optString("image"), card.optString("imageCaption"));
        String label = type.replace('_', ' ').toUpperCase();
        TextView kind = text(label, 11, "cover".equals(type) ? CrewTheme.TEAL_300 : CrewTheme.TEAL_400, true); kind.setLetterSpacing(.10f); surface.addView(kind);
        TextView title = text(card.optString("title"), "cover".equals(type) ? 32 : 25, CrewTheme.TEXT_PRIMARY, true); title.setPadding(0, dp(10), 0, dp(8)); surface.addView(title);
        if (!card.optString("subtitle").isEmpty()) { TextView sub = text(card.optString("subtitle"), 16, CrewTheme.TEAL_300, false); sub.setPadding(0, 0, 0, dp(12)); surface.addView(sub); }
        if (!card.optString("body").isEmpty()) { TextView body = text(card.optString("body"), 17, CrewTheme.TEXT_SECONDARY, false); body.setLineSpacing(dp(5), 1f); surface.addView(body); }
        if ("metric".equals(type)) addMetrics(surface, card.optJSONArray("metrics"));
        if ("timeline".equals(type)) addItems(surface, card.optJSONArray("items"), true);
        if ("compare".equals(type)) addItems(surface, card.optJSONArray("items"), false);
        addFacts(surface, card.optJSONArray("facts"));
        if (!card.optString("speakerNotes").isEmpty()) {
            TextView note = text("🎙️ " + card.optString("speakerNotes"), 12, CrewTheme.TEXT_MUTED, false); note.setPadding(0, dp(22), 0, 0); surface.addView(note);
        }
    }

    private void addImageIfPresent(LinearLayout host, DeckRepository.Deck deck, String relative, String caption) {
        if (relative == null || relative.trim().isEmpty() || deck == null) return;
        try {
            File file = new File(deck.directory, relative).getCanonicalFile();
            if (!file.getPath().startsWith(deck.directory.getCanonicalPath() + File.separator) || !file.isFile()) return;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath()); if (bitmap == null) return;
            ImageView image = new ImageView(this); image.setImageBitmap(bitmap); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(CrewTheme.createCard(this, CrewTheme.BG_ELEVATED, Color.TRANSPARENT, 18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)); lp.setMargins(0, 0, 0, dp(20)); host.addView(image, lp);
            if (caption != null && !caption.trim().isEmpty()) { TextView label = text(caption, 11, CrewTheme.TEXT_MUTED, false); label.setPadding(0, 0, 0, dp(14)); host.addView(label); }
        } catch (Exception ignored) {}
    }

    private void addMetrics(LinearLayout host, JSONArray metrics) {
        if (metrics == null) return;
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, dp(20), 0, 0);
        for (int i = 0; i < metrics.length(); i++) {
            JSONObject metric = metrics.optJSONObject(i); if (metric == null) continue;
            LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(14), dp(12), dp(14), dp(12)); box.setBackground(CrewTheme.createCard(this, CrewTheme.BG_ELEVATED, CrewTheme.BORDER_INDIGO, 16));
            box.addView(text(metric.optString("value"), 24, CrewTheme.TEAL_300, true)); box.addView(text(metric.optString("label"), 11, CrewTheme.TEXT_SECONDARY, false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); if (i > 0) lp.setMargins(dp(10), 0, 0, 0); row.addView(box, lp);
        }
        host.addView(row);
    }

    private void addItems(LinearLayout host, JSONArray items, boolean timeline) {
        if (items == null) return;
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, dp(18), 0, 0);
        for (int i = 0; i < items.length(); i++) { TextView item = text((timeline ? "●  " : "•  ") + items.optString(i), 15, CrewTheme.TEXT_PRIMARY, false); item.setPadding(0, dp(7), 0, dp(7)); list.addView(item); }
        host.addView(list);
    }

    private void addFacts(LinearLayout host, JSONArray facts) {
        if (facts == null || facts.length() == 0) return;
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, dp(16), 0, 0);
        for (int i = 0; i < facts.length(); i++) { TextView fact = text("✦  " + facts.optString(i), 13, CrewTheme.TEAL_300, false); fact.setPadding(0, dp(3), 0, dp(3)); list.addView(fact); }
        host.addView(list);
    }

    private TextView text(String value, float size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value == null ? "" : value); view.setTextSize(size); view.setTextColor(color); view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); return view; }
    private TextView button(String value, int color) { TextView view = text(value, 23, CrewTheme.TEXT_PRIMARY, true); view.setGravity(Gravity.CENTER); view.setBackground(CrewTheme.createCard(this, color, Color.TRANSPARENT, 16)); return view; }
    private int dp(float value) { return CrewTheme.dp(this, value); }
}
