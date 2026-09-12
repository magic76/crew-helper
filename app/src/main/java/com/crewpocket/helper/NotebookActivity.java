package com.crewpocket.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Simple local UI for Crew Notebook. */
public class NotebookActivity extends Activity {
    private NoteStore store;
    private LinearLayout listContainer;
    private EditText searchInput;

    private int dp(float value) {
        return CrewTheme.dp(this, value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(CrewTheme.BG_PRIMARY);
            getWindow().setNavigationBarColor(CrewTheme.BG_PRIMARY);
        }

        store = new NoteStore(this);
        setContentView(buildPage());
        renderNotes("");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (listContainer != null) {
            renderNotes(
                    searchInput == null ? "" : searchInput.getText().toString());
        }
    }

    private View buildPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CrewTheme.BG_PRIMARY);
        root.setPadding(dp(18), dp(18), dp(18), dp(12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(24);
        back.setTextColor(CrewTheme.TEXT_PRIMARY);
        back.setAllCaps(false);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(I18n.get(this, "Crew Notebook", "Crew Notebook"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        heading.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(I18n.get(
                this,
                "只保存你明確交給 Crew 的長期資訊",
                "Only information you explicitly save is kept here"));
        subtitle.setTextSize(10.5f);
        subtitle.setTextColor(CrewTheme.TEXT_SECONDARY);
        heading.addView(subtitle);

        header.addView(
                heading,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        Button add = new Button(this);
        add.setText("+");
        add.setTextSize(22);
        add.setTextColor(CrewTheme.TEAL_300);
        add.setAllCaps(false);
        add.setBackground(CrewTheme.createCard(
                this,
                Color.argb(35, 20, 184, 166),
                CrewTheme.BORDER_TEAL,
                14));
        add.setOnClickListener(v -> showEditor(null));
        header.addView(add, new LinearLayout.LayoutParams(dp(46), dp(42)));

        root.addView(header);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint(I18n.get(this, "搜尋記事", "Search notes"));
        searchInput.setTextSize(13);
        searchInput.setTextColor(CrewTheme.TEXT_PRIMARY);
        searchInput.setHintTextColor(CrewTheme.TEXT_MUTED);
        searchInput.setPadding(dp(14), 0, dp(14), 0);
        searchInput.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                14));

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46));
        searchLp.setMargins(0, dp(16), 0, dp(12));
        root.addView(searchInput, searchLp);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(
                    CharSequence s, int start, int before, int count) {
                renderNotes(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

        return root;
    }

    private void renderNotes(String query) {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        JSONArray notes = query == null || query.trim().isEmpty()
                ? store.list(100)
                : store.search(query, 100);

        if (notes.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(I18n.get(
                    this,
                    "還沒有記事。\n你可以直接對 Crew 說「把這個記下來」。",
                    "No notes yet.\nTell Crew: “Save this to my notebook.”"));
            empty.setTextSize(13);
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(CrewTheme.TEXT_SECONDARY);
            empty.setPadding(dp(20), dp(64), dp(20), dp(20));
            listContainer.addView(empty);
            return;
        }

        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note != null) listContainer.addView(noteCard(note));
        }
    }

    private View noteCard(final JSONObject summary) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(CrewTheme.createCard(
                this,
                CrewTheme.BG_SURFACE,
                CrewTheme.BORDER_SUBTLE,
                14));

        TextView title = new TextView(this);
        title.setText(summary.optString("title", "Untitled"));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(CrewTheme.TEXT_PRIMARY);
        card.addView(title);

        String preview = summary.optString("preview", "");
        if (!preview.isEmpty()) {
            TextView body = new TextView(this);
            body.setText(preview);
            body.setTextSize(11.5f);
            body.setTextColor(CrewTheme.TEXT_SECONDARY);
            body.setMaxLines(3);
            body.setPadding(0, dp(5), 0, 0);
            card.addView(body);
        }

        String source = summary.optString("sourceUrl", "");
        String tags = NoteStore.tagsToText(summary.optJSONArray("tags"));
        String meta = !tags.isEmpty() ? tags : source;
        if (meta.isEmpty()) {
            meta = formatTime(summary.optLong("updatedAt", 0L));
        } else {
            meta = meta + " · " + formatTime(summary.optLong("updatedAt", 0L));
        }

        TextView footer = new TextView(this);
        footer.setText(meta);
        footer.setTextSize(9.5f);
        footer.setTextColor(CrewTheme.TEXT_MUTED);
        footer.setSingleLine(true);
        footer.setPadding(0, dp(7), 0, 0);
        card.addView(footer);

        final String id = summary.optString("id");
        card.setOnClickListener(v -> {
            JSONObject note = store.get(id);
            if (note.optBoolean("success", false)) showEditor(note);
        });

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        outer.addView(card, lp);
        return outer;
    }

    private void showEditor(final JSONObject existing) {
        final boolean editing = existing != null;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);

        final EditText title = editorField(
                I18n.get(this, "標題", "Title"),
                false);
        title.setText(editing ? existing.optString("title") : "");
        form.addView(title);

        final EditText content = editorField(
                I18n.get(this, "內容", "Content"),
                true);
        content.setText(editing ? existing.optString("content") : "");
        form.addView(content);

        final EditText source = editorField(
                I18n.get(this, "來源網址（選填）", "Source URL (optional)"),
                false);
        source.setText(editing ? existing.optString("sourceUrl") : "");
        form.addView(source);

        final EditText tags = editorField(
                I18n.get(this, "標籤，以逗號分隔", "Tags, comma separated"),
                false);
        tags.setText(editing
                ? NoteStore.tagsToText(existing.optJSONArray("tags"))
                : "");
        form.addView(tags);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(editing
                        ? I18n.get(this, "編輯記事", "Edit note")
                        : I18n.get(this, "新增記事", "New note"))
                .setView(form)
                .setPositiveButton(
                        I18n.get(this, "儲存", "Save"),
                        null)
                .setNegativeButton(
                        I18n.get(this, "取消", "Cancel"),
                        null);

        if (editing) {
            builder.setNeutralButton(
                    I18n.get(this, "刪除", "Delete"),
                    null);
        }

        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String noteTitle = title.getText().toString().trim();
                        String noteContent = content.getText().toString().trim();
                        if (noteTitle.isEmpty() && noteContent.isEmpty()) {
                            Toast.makeText(
                                    NotebookActivity.this,
                                    I18n.get(
                                            NotebookActivity.this,
                                            "請輸入標題或內容",
                                            "Enter a title or content"),
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }

                        if (noteTitle.isEmpty()) {
                            noteTitle = noteContent.length() > 32
                                    ? noteContent.substring(0, 32)
                                    : noteContent;
                        }

                        if (editing) {
                            JSONObject patch = new JSONObject();
                            try {
                                patch.put("title", noteTitle);
                                patch.put("content", noteContent);
                                patch.put(
                                        "sourceUrl",
                                        source.getText().toString().trim());
                                patch.put(
                                        "tags",
                                        NoteStore.parseTags(
                                                tags.getText().toString()));
                            } catch (Exception ignored) {}
                            store.update(existing.optString("id"), patch);
                        } else {
                            store.create(
                                    noteTitle,
                                    noteContent,
                                    source.getText().toString().trim(),
                                    NoteStore.parseTags(
                                            tags.getText().toString()));
                        }

                        dialog.dismiss();
                        renderNotes(searchInput.getText().toString());
                    });

            if (editing) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener(v -> confirmDelete(
                                dialog,
                                existing.optString("id")));
            }
        });
        dialog.show();
    }

    private EditText editorField(String hint, boolean multiline) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(CrewTheme.TEXT_PRIMARY);
        field.setHintTextColor(CrewTheme.TEXT_MUTED);
        field.setTextSize(13);
        field.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (multiline) {
            field.setMinLines(6);
            field.setGravity(Gravity.TOP);
            field.setSingleLine(false);
        } else {
            field.setSingleLine(true);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        field.setLayoutParams(lp);
        return field;
    }

    private void confirmDelete(
            final AlertDialog editor,
            final String noteId) {
        new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, "刪除記事？", "Delete note?"))
                .setMessage(I18n.get(
                        this,
                        "這個動作無法復原。",
                        "This cannot be undone."))
                .setPositiveButton(
                        I18n.get(this, "刪除", "Delete"),
                        (dialog, which) -> {
                            store.delete(noteId);
                            editor.dismiss();
                            renderNotes(searchInput.getText().toString());
                        })
                .setNegativeButton(
                        I18n.get(this, "取消", "Cancel"),
                        null)
                .show();
    }

    private String formatTime(long timestamp) {
        if (timestamp <= 0L) return "";
        return new SimpleDateFormat(
                "yyyy/MM/dd HH:mm",
                Locale.getDefault())
                .format(new Date(timestamp));
    }
}
