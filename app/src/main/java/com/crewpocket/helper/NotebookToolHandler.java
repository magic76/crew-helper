package com.crewpocket.helper;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** 0104: Crew Notebook persistence/tool execution extracted from Live transport. */
final class NotebookToolHandler {
    private final Context appContext;
    private final ReadOnlyNotebookHarness readOnlyHarness;

    NotebookToolHandler(Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
        this.readOnlyHarness = new ReadOnlyNotebookHarness(new ReadOnlyNotebookHarness.Backend() {
            @Override public JSONObject getNote(JSONObject args) {
                return NotebookToolHandler.this.getNotebookNote(args);
            }

            @Override public JSONObject searchNotes(JSONObject args) {
                return NotebookToolHandler.this.searchNotebookNotes(args);
            }

            @Override public JSONObject listNotes(JSONObject args) {
                return NotebookToolHandler.this.listNotebookNotes(args);
            }
        });
    }

    static boolean handles(String name) {
        return "create_note".equals(name)
                || "update_note".equals(name)
                || "get_note".equals(name)
                || "search_notes".equals(name)
                || "list_notes".equals(name)
                || "delete_note".equals(name);
    }

    JSONObject execute(String name, JSONObject args) {
        JSONObject safeArgs = args == null ? new JSONObject() : args;
        if ("create_note".equals(name)) return createNotebookNote(safeArgs);
        if ("update_note".equals(name)) return updateNotebookNote(safeArgs);
        if ("get_note".equals(name)
                || "search_notes".equals(name)
                || "list_notes".equals(name)) {
            return readOnlyHarness.execute(name, safeArgs);
        }
        if ("delete_note".equals(name)) return deleteNotebookNote(safeArgs);
        return notebookError("UNSUPPORTED_NOTEBOOK_TOOL");
    }

    private NoteStore notebookStore() {
        return appContext == null ? null : new NoteStore(appContext);
    }

    private JSONObject createNotebookNote(JSONObject args) {
        NoteStore store = notebookStore();
        if (store == null) return notebookError("NOTEBOOK_CONTEXT_UNAVAILABLE");
        JSONArray tags = args.optJSONArray("tags");
        return store.create(args.optString("title", ""), args.optString("content", ""),
                args.optString("source_url", ""), tags == null ? new JSONArray() : tags);
    }

    private JSONObject updateNotebookNote(JSONObject args) {
        NoteStore store = notebookStore();
        if (store == null) return notebookError("NOTEBOOK_CONTEXT_UNAVAILABLE");
        JSONObject patch = new JSONObject();
        try {
            if (args.has("title")) patch.put("title", args.optString("title"));
            if (args.has("content")) patch.put("content", args.optString("content"));
            if (args.has("source_url")) patch.put("sourceUrl", args.optString("source_url"));
            if (args.has("tags")) patch.put("tags", args.optJSONArray("tags"));
        } catch (Exception ignored) {}
        return store.update(args.optString("note_id", ""), patch);
    }

    private JSONObject getNotebookNote(JSONObject args) {
        NoteStore store = notebookStore();
        return store == null ? notebookError("NOTEBOOK_CONTEXT_UNAVAILABLE") : store.get(args.optString("note_id", ""));
    }

    private JSONObject searchNotebookNotes(JSONObject args) {
        NoteStore store = notebookStore();
        if (store == null) return notebookError("NOTEBOOK_CONTEXT_UNAVAILABLE");
        JSONObject out = new JSONObject();
        try { JSONArray notes = store.search(args.optString("query", ""), args.optInt("limit", 20)); out.put("success", true).put("notes", notes).put("count", notes.length()); }
        catch (Exception ignored) {}
        return out;
    }

    private JSONObject listNotebookNotes(JSONObject args) {
        NoteStore store = notebookStore();
        if (store == null) return notebookError("NOTEBOOK_CONTEXT_UNAVAILABLE");
        JSONObject out = new JSONObject();
        try { JSONArray notes = store.list(args.optInt("limit", 20)); out.put("success", true).put("notes", notes).put("count", notes.length()); }
        catch (Exception ignored) {}
        return out;
    }

    private JSONObject deleteNotebookNote(JSONObject args) {
        NoteStore store = notebookStore();
        if (store == null) return notebookError("NOTEBOOK_CONTEXT_UNAVAILABLE");
        boolean deleted = store.delete(args.optString("note_id", ""));
        JSONObject out = new JSONObject();
        try { out.put("success", deleted); if (!deleted) out.put("error", "NOTE_NOT_FOUND"); }
        catch (Exception ignored) {}
        return out;
    }

    private JSONObject notebookError(String code) {
        JSONObject out = new JSONObject();
        try { out.put("success", false).put("error", code); } catch (Exception ignored) {}
        return out;
    }
}
