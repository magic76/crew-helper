package com.crewpocket.helper;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/**
 * Local, explicit long-term notebook.
 *
 * Notes are only written when the user or Notebook UI asks for it. This store
 * is intentionally separate from WorkingContext and implicit memory.
 */
final class NoteStore {
    private static final String FILE_NAME = "crew_notebook.json";
    private static final int MAX_NOTES = 1000;
    private static final int MAX_TITLE = 180;
    private static final int MAX_CONTENT = 50_000;
    private static final int MAX_SOURCE = 2_000;
    private static final int MAX_TAGS = 12;
    private static final int MAX_TAG = 48;
    private static final int MAX_STORE_BYTES = 60_000_000;

    private final File file;
    private final File tempFile;

    NoteStore(Context context) {
        Context app = context.getApplicationContext();
        file = new File(app.getFilesDir(), FILE_NAME);
        tempFile = new File(app.getFilesDir(), FILE_NAME + ".tmp");
    }

    synchronized int count() {
        return loadNotes().length();
    }

    synchronized JSONObject create(
            String title,
            String content,
            String sourceUrl,
            JSONArray tags) {
        JSONArray notes = loadNotes();
        long now = System.currentTimeMillis();

        String cleanTitle = sanitize(title, MAX_TITLE, "Untitled");
        String cleanContent = sanitize(content, MAX_CONTENT, "");
        String cleanSource = sanitize(sourceUrl, MAX_SOURCE, "");

        // Guard against transport/model duplicate delivery in one user turn.
        for (int i = 0; i < notes.length(); i++) {
            JSONObject existing = notes.optJSONObject(i);
            if (existing == null) continue;
            if (now - existing.optLong("updatedAt", 0L) > 15_000L) continue;
            if (cleanTitle.equals(existing.optString("title"))
                    && cleanContent.equals(existing.optString("content"))
                    && cleanSource.equals(existing.optString("sourceUrl"))) {
                JSONObject duplicate = cloneJson(existing);
                try {
                    duplicate.put("success", true);
                    duplicate.put("idempotent", true);
                } catch (Exception ignored) {}
                return duplicate;
            }
        }

        JSONObject note = new JSONObject();
        try {
            note.put("id", "note_" + UUID.randomUUID().toString());
            note.put("title", cleanTitle);
            note.put("content", cleanContent);
            note.put("sourceUrl", cleanSource);
            note.put("tags", sanitizeTags(tags));
            note.put("createdAt", now);
            note.put("updatedAt", now);
        } catch (Exception ignored) {}

        JSONArray next = new JSONArray();
        next.put(note);
        for (int i = 0; i < notes.length() && next.length() < MAX_NOTES; i++) {
            JSONObject old = notes.optJSONObject(i);
            if (old != null) next.put(old);
        }
        saveNotes(next);
        JSONObject created = cloneJson(note);
        try { created.put("success", true); } catch (Exception ignored) {}
        return created;
    }

    synchronized JSONObject update(String id, JSONObject patch) {
        if (id == null || id.trim().isEmpty()) {
            return error("NOTE_ID_REQUIRED");
        }

        JSONArray notes = loadNotes();
        JSONObject updated = null;

        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null || !id.equals(note.optString("id"))) continue;

            try {
                if (patch.has("title")) {
                    note.put(
                            "title",
                            sanitize(
                                    patch.optString("title"),
                                    MAX_TITLE,
                                    note.optString("title", "Untitled")));
                }
                if (patch.has("content")) {
                    note.put(
                            "content",
                            sanitize(
                                    patch.optString("content"),
                                    MAX_CONTENT,
                                    ""));
                }
                if (patch.has("sourceUrl")) {
                    note.put(
                            "sourceUrl",
                            sanitize(
                                    patch.optString("sourceUrl"),
                                    MAX_SOURCE,
                                    ""));
                }
                if (patch.has("tags")) {
                    note.put(
                            "tags",
                            sanitizeTags(patch.optJSONArray("tags")));
                }
                note.put("updatedAt", System.currentTimeMillis());
                updated = cloneJson(note);
            } catch (Exception ignored) {}
            break;
        }

        if (updated == null) return error("NOTE_NOT_FOUND");
        saveNotes(sortByUpdatedDesc(notes));
        return updated;
    }

    synchronized JSONObject get(String id) {
        if (id == null || id.trim().isEmpty()) return error("NOTE_ID_REQUIRED");
        JSONArray notes = loadNotes();
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note != null && id.equals(note.optString("id"))) {
                JSONObject out = cloneJson(note);
                try { out.put("success", true); } catch (Exception ignored) {}
                return out;
            }
        }
        return error("NOTE_NOT_FOUND");
    }

    synchronized JSONArray list(int limit) {
        JSONArray notes = sortByUpdatedDesc(loadNotes());
        JSONArray out = new JSONArray();
        int max = clampLimit(limit);
        for (int i = 0; i < notes.length() && out.length() < max; i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note != null) out.put(summary(note));
        }
        return out;
    }

    synchronized JSONArray search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return list(limit);

        JSONArray notes = sortByUpdatedDesc(loadNotes());
        ArrayList<JSONObject> matches = new ArrayList<JSONObject>();

        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;

            StringBuilder hay = new StringBuilder();
            hay.append(note.optString("title", "")).append('\n');
            hay.append(note.optString("content", "")).append('\n');
            hay.append(note.optString("sourceUrl", "")).append('\n');
            JSONArray tags = note.optJSONArray("tags");
            if (tags != null) {
                for (int j = 0; j < tags.length(); j++) {
                    hay.append(tags.optString(j)).append(' ');
                }
            }

            if (hay.toString().toLowerCase(Locale.ROOT).contains(q)) {
                matches.add(note);
            }
        }

        JSONArray out = new JSONArray();
        int max = clampLimit(limit);
        for (JSONObject note : matches) {
            if (out.length() >= max) break;
            out.put(summary(note));
        }
        return out;
    }

    synchronized boolean delete(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        JSONArray notes = loadNotes();
        JSONArray next = new JSONArray();
        boolean deleted = false;

        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            if (id.equals(note.optString("id"))) {
                deleted = true;
                continue;
            }
            next.put(note);
        }

        if (deleted) saveNotes(next);
        return deleted;
    }

    private JSONArray loadNotes() {
        if (!file.exists()) return new JSONArray();
        try {
            FileInputStream in = new FileInputStream(file);
            if (file.length() <= 0L || file.length() > MAX_STORE_BYTES) {
                in.close();
                return new JSONArray();
            }
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            in.close();
            if (read <= 0) return new JSONArray();

            JSONObject root = new JSONObject(
                    new String(data, 0, read, StandardCharsets.UTF_8));
            JSONArray notes = root.optJSONArray("notes");
            return notes == null ? new JSONArray() : notes;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void saveNotes(JSONArray notes) {
        try {
            JSONObject root = new JSONObject()
                    .put("version", 1)
                    .put("notes", notes);

            FileOutputStream out = new FileOutputStream(tempFile, false);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            try { out.getFD().sync(); } catch (Exception ignored) {}
            out.close();

            if (file.exists() && !file.delete()) {
                throw new Exception("unable to replace note store");
            }
            if (!tempFile.renameTo(file)) {
                FileInputStream in = new FileInputStream(tempFile);
                FileOutputStream fallback = new FileOutputStream(file, false);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fallback.write(buf, 0, n);
                fallback.flush();
                fallback.close();
                in.close();
                tempFile.delete();
            }
        } catch (Exception ignored) {}
    }

    private JSONArray sortByUpdatedDesc(JSONArray source) {
        ArrayList<JSONObject> list = new ArrayList<JSONObject>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject note = source.optJSONObject(i);
            if (note != null) list.add(note);
        }

        Collections.sort(list, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(
                        b.optLong("updatedAt", 0L),
                        a.optLong("updatedAt", 0L));
            }
        });

        JSONArray out = new JSONArray();
        for (JSONObject note : list) out.put(note);
        return out;
    }

    private JSONObject summary(JSONObject note) {
        JSONObject out = new JSONObject();
        try {
            String content = note.optString("content", "");
            String preview = content.replaceAll("\\s+", " ").trim();
            if (preview.length() > 320) preview = preview.substring(0, 320);

            out.put("id", note.optString("id"));
            out.put("title", note.optString("title"));
            out.put("preview", preview);
            out.put("sourceUrl", note.optString("sourceUrl"));
            out.put("tags", note.optJSONArray("tags") == null
                    ? new JSONArray()
                    : note.optJSONArray("tags"));
            out.put("createdAt", note.optLong("createdAt"));
            out.put("updatedAt", note.optLong("updatedAt"));
        } catch (Exception ignored) {}
        return out;
    }

    private JSONArray sanitizeTags(JSONArray tags) {
        JSONArray out = new JSONArray();
        if (tags == null) return out;
        for (int i = 0; i < tags.length() && out.length() < MAX_TAGS; i++) {
            String tag = sanitize(tags.optString(i), MAX_TAG, "");
            if (tag.isEmpty()) continue;

            boolean duplicate = false;
            for (int j = 0; j < out.length(); j++) {
                if (tag.equalsIgnoreCase(out.optString(j))) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.put(tag);
        }
        return out;
    }

    static JSONArray parseTags(String raw) {
        JSONArray out = new JSONArray();
        if (raw == null) return out;
        String[] parts = raw.split("[,，#\\n]");
        for (String part : parts) {
            String tag = part == null ? "" : part.trim();
            if (!tag.isEmpty()) out.put(tag);
        }
        return out;
    }

    static String tagsToText(JSONArray tags) {
        if (tags == null || tags.length() == 0) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tags.length(); i++) {
            String tag = tags.optString(i).trim();
            if (tag.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(tag);
        }
        return out.toString();
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return 20;
        return Math.max(1, Math.min(100, limit));
    }

    private String sanitize(String value, int max, String fallback) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) result = fallback == null ? "" : fallback;
        if (result.length() > max) result = result.substring(0, max);
        return result;
    }

    private JSONObject cloneJson(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONObject error(String code) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false);
            out.put("error", code);
        } catch (Exception ignored) {}
        return out;
    }
}
