package com.crewpocket.helper;

import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * A small, deliberately closed Deck data boundary. Gemini receives card IDs
 * and summaries, never arbitrary file paths or HTML/JavaScript to execute.
 */
final class DeckRepository {
    private static final String TAG = "CrewDeck";
    private static final String ROOT = "decks";
    private static final Object LOCK = new Object();
    private static Context appContext;
    private static Deck activeDeck;
    private static int activeIndex;

    static final class Deck {
        final String id;
        final String title;
        final String theme;
        JSONArray cards;
        final File directory;
        Deck(JSONObject source, File directory) {
            id = source.optString("deckId", "");
            title = source.optString("title", id);
            theme = source.optString("theme", "dark-tech");
            cards = source.optJSONArray("cards") == null ? new JSONArray() : source.optJSONArray("cards");
            this.directory = directory;
        }
    }

    private DeckRepository() {}

    static void initialize(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            appContext = context.getApplicationContext();
            ensureBundledDeck();
        }
    }

    private static File root() { return appContext == null ? null : new File(appContext.getFilesDir(), ROOT); }

    /** A ready-to-run template is copied once; user Decks live beside it. */
    private static void ensureBundledDeck() {
        try {
            File root = root();
            if (root == null) return;
            File out = new File(new File(root, "welcome-deck"), "deck.json");
            if (out.exists()) return;
            if (!out.getParentFile().exists() && !out.getParentFile().mkdirs()) return;
            InputStream in = appContext.getAssets().open("decks/welcome-deck/deck.json");
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) > 0) fos.write(buffer, 0, count);
            fos.close(); in.close();
        } catch (Exception error) { Log.w(TAG, "無法建立範例 Deck：" + error.getMessage()); }
    }

    static JSONObject listDecks() {
        JSONObject result = new JSONObject(); JSONArray decks = new JSONArray();
        synchronized (LOCK) {
            ensureBundledDeck();
            File base = root(); File[] dirs = base == null ? null : base.listFiles();
            if (dirs != null) for (File dir : dirs) {
                File json = new File(dir, "deck.json");
                if (!dir.isDirectory() || !json.isFile()) continue;
                try {
                    Deck deck = readDeck(json);
                    decks.put(new JSONObject().put("deckId", deck.id).put("title", deck.title).put("cards", deck.cards.length()));
                } catch (Exception ignored) {}
            }
        }
        try { result.put("success", true).put("decks", decks).put("deckFolder", root() == null ? "" : root().getAbsolutePath()); }
        catch (Exception ignored) {}
        return result;
    }

    /** Imports an SAF-selected folder, preserving relative image paths without broad storage permission. */
    static JSONObject importDeckTree(Context context, Uri treeUri) {
        if (context == null || treeUri == null) return failure("沒有選擇資料夾");
        initialize(context);
        File staging = null;
        try {
            try { context.getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            File base = root();
            staging = new File(base, ".import_" + System.currentTimeMillis());
            if (!staging.mkdirs()) throw new Exception("無法建立匯入暫存資料夾");
            int[] count = new int[]{0}; long[] bytes = new long[]{0L};
            copyTree(context.getContentResolver(), treeUri, DocumentsContract.getTreeDocumentId(treeUri), staging, count, bytes);
            File manifest = new File(staging, "deck.json");
            if (!manifest.isFile()) throw new Exception("選擇的資料夾根目錄必須包含 deck.json");
            Deck deck = readDeck(manifest);
            if (!deck.id.matches("[A-Za-z0-9_-]{1,64}") || deck.cards.length() == 0) throw new Exception("deck.json 缺少合法 deckId 或 cards");
            File destination = new File(base, deck.id);
            if (destination.exists()) throw new Exception("Deck「" + deck.id + "」已存在；請改 deckId 後再匯入");
            if (!staging.renameTo(destination)) throw new Exception("無法完成資料夾匯入");
            staging = null;
            return new JSONObject().put("success", true).put("deckId", deck.id).put("title", deck.title).put("files", count[0]).put("bytes", bytes[0]);
        } catch (Exception error) { return failure("匯入失敗：" + error.getMessage()); }
        finally { if (staging != null) deleteTree(staging); }
    }

    private static void copyTree(ContentResolver resolver, Uri treeUri, String parentId, File destination, int[] fileCount, long[] totalBytes) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
        if (cursor == null) throw new Exception("無法讀取資料夾");
        try {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0), name = cursor.getString(1), mime = cursor.getString(2);
                if (name == null || !name.matches("[A-Za-z0-9._ -]{1,120}")) throw new Exception("資料夾含有不支援的檔名");
                Uri child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                File out = new File(destination, name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!out.mkdir()) throw new Exception("無法建立子資料夾：" + name);
                    copyTree(resolver, treeUri, id, out, fileCount, totalBytes);
                } else {
                    if (++fileCount[0] > 100) throw new Exception("Deck 最多 100 個檔案");
                    InputStream in = resolver.openInputStream(child); if (in == null) throw new Exception("無法讀取檔案：" + name);
                    FileOutputStream fos = new FileOutputStream(out); byte[] buffer = new byte[8192]; int size;
                    while ((size = in.read(buffer)) > 0) { totalBytes[0] += size; if (totalBytes[0] > 25L * 1024L * 1024L) throw new Exception("Deck 最大 25 MB"); fos.write(buffer, 0, size); }
                    fos.close(); in.close();
                }
            }
        } finally { cursor.close(); }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] entries = file.listFiles(); if (entries != null) for (File entry : entries) deleteTree(entry); }
        file.delete();
    }

    static JSONObject openDeck(String requestedId) {
        synchronized (LOCK) {
            try {
                Deck deck = loadDeck(requestedId);
                activeDeck = deck; activeIndex = 0;
                launchDeckActivity();
                return cardResult(deck.cards.getJSONObject(0), 0, true).put("message", "已開啟 Deck：「" + deck.title + "」");
            } catch (Exception error) { return failure("無法開啟 Deck：" + error.getMessage()); }
        }
    }

    /** Creates a session-only Deck from model-provided, schema-normalized text. It is never written to disk. */
    static JSONObject createEphemeralDeck(String requestedTitle, JSONArray requestedCards) {
        synchronized (LOCK) {
            try {
                if (requestedCards == null || requestedCards.length() < 1 || requestedCards.length() > 12) throw new Exception("即席 Deck 需要 1–12 張卡片");
                JSONArray cards = new JSONArray();
                java.util.HashSet<String> ids = new java.util.HashSet<String>();
                for (int i = 0; i < requestedCards.length(); i++) {
                    JSONObject input = requestedCards.optJSONObject(i); if (input == null) throw new Exception("第 " + (i + 1) + " 張卡片格式不正確");
                    String id = input.optString("id", "card-" + (i + 1)).replaceAll("[^A-Za-z0-9_-]", "-");
                    if (id.isEmpty() || !ids.add(id)) id = "card-" + (i + 1);
                    String type = input.optString("type", "content");
                    if (!("cover".equals(type) || "content".equals(type) || "metric".equals(type) || "timeline".equals(type) || "compare".equals(type))) type = "content";
                    JSONObject card = new JSONObject().put("id", id).put("type", type)
                            .put("title", clip(input.optString("title", "第 " + (i + 1) + " 張"), 100))
                            .put("body", clip(input.optString("body"), 1200))
                            .put("subtitle", clip(input.optString("subtitle"), 180))
                            .put("speakerNotes", clip(input.optString("speakerNotes", input.optString("speaker_notes")), 700))
                            .put("facts", normalizeStrings(input.optJSONArray("facts"), 8, 140))
                            .put("items", normalizeStrings(input.optJSONArray("items"), 8, 180));
                    if ("metric".equals(type)) card.put("metrics", normalizeMetrics(input.optJSONArray("metrics")));
                    JSONArray next = new JSONArray(); if (i + 1 < requestedCards.length()) next.put("card-" + (i + 2)); card.put("next", next);
                    cards.put(card);
                }
                // Use canonical sequential IDs for deterministic next-card navigation.
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject card = cards.getJSONObject(i); card.put("id", "card-" + (i + 1));
                    JSONArray next = new JSONArray(); if (i + 1 < cards.length()) next.put("card-" + (i + 2)); card.put("next", next);
                }
                JSONObject source = new JSONObject().put("deckId", "ephemeral_" + System.currentTimeMillis())
                        .put("title", clip(requestedTitle, 100)).put("theme", "dark-tech").put("cards", cards);
                activeDeck = new Deck(source, null); activeIndex = 0;
                launchDeckActivity();
                return cardResult(cards.getJSONObject(0), 0, true).put("ephemeral", true)
                        .put("message", "已建立即席 Deck；這是模型既有知識的整理，不保證為最新資料。請先顯示此卡並以語音介紹。");
            } catch (Exception error) { return failure("無法建立即席 Deck：" + error.getMessage()); }
        }
    }

    private static String clip(String value, int max) { value = value == null ? "" : value.trim(); return value.length() > max ? value.substring(0, max) : value; }
    private static JSONArray normalizeStrings(JSONArray source, int maxCount, int maxLength) {
        JSONArray result = new JSONArray(); if (source == null) return result;
        for (int i = 0; i < source.length() && i < maxCount; i++) result.put(clip(source.optString(i), maxLength));
        return result;
    }
    private static JSONArray normalizeMetrics(JSONArray source) {
        JSONArray result = new JSONArray(); if (source == null) return result;
        for (int i = 0; i < source.length() && i < 4; i++) {
            JSONObject metric = source.optJSONObject(i);
            if (metric != null) try { result.put(new JSONObject().put("label", clip(metric.optString("label"), 50)).put("value", clip(metric.optString("value"), 40))); }
            catch (Exception ignored) {}
        }
        return result;
    }

    static JSONObject getCard(String id) {
        synchronized (LOCK) {
            if (activeDeck == null) return failure("尚未開啟 Deck，請先使用 open_deck。");
            int index = id == null || id.trim().isEmpty() ? activeIndex : findCard(id);
            if (index < 0) return failure("找不到此卡片 ID：" + id);
            try { return cardResult(activeDeck.cards.getJSONObject(index), index, false); }
            catch (Exception error) { return failure("讀取卡片失敗：" + error.getMessage()); }
        }
    }

    static JSONObject presentCard(String id) {
        synchronized (LOCK) {
            if (activeDeck == null) return failure("尚未開啟 Deck，請先使用 open_deck。");
            int index = id == null || id.trim().isEmpty() ? activeIndex : findCard(id);
            if (index < 0) return failure("找不到此卡片 ID：" + id);
            activeIndex = index;
            launchDeckActivity();
            DeckActivity.showActiveCard();
            try { return cardResult(activeDeck.cards.getJSONObject(index), index, true).put("message", "正在顯示第 " + (index + 1) + " 張卡片"); }
            catch (Exception error) { return failure("切換卡片失敗：" + error.getMessage()); }
        }
    }

    static JSONObject advance() {
        synchronized (LOCK) {
            if (activeDeck == null) return failure("尚未開啟 Deck。");
            if (activeIndex >= activeDeck.cards.length() - 1) return failure("已在最後一張卡片；請作結或依使用者問題選擇其他卡片。");
            activeIndex++;
            DeckActivity.showActiveCard();
            try { return cardResult(activeDeck.cards.getJSONObject(activeIndex), activeIndex, true).put("message", "已前往下一張卡片"); }
            catch (Exception error) { return failure("前往下一張失敗：" + error.getMessage()); }
        }
    }

    static JSONObject listDeckImages() {
        synchronized (LOCK) {
            if (activeDeck == null) return failure("尚未開啟 Deck。");
            if (activeDeck.directory == null) return failure("即席 Deck 沒有匯入圖片；請使用已匯入 Deck 的圖片資料夾。");
            JSONArray images = new JSONArray();
            collectImages(activeDeck.directory, activeDeck.directory, images);
            try { return new JSONObject().put("success", true).put("images", images).put("count", images.length()); }
            catch (Exception error) { return failure("讀取圖片失敗：" + error.getMessage()); }
        }
    }

    static JSONObject attachImageToFutureCard(String cardId, String assetId, String caption) {
        synchronized (LOCK) {
            try {
                if (activeDeck == null || activeDeck.directory == null) throw new Exception("目前 Deck 沒有可用的匯入圖片資料夾");
                int index = requireFutureCard(cardId);
                File asset = new File(activeDeck.directory, assetId).getCanonicalFile();
                if (!asset.getPath().startsWith(activeDeck.directory.getCanonicalPath() + File.separator) || !asset.isFile() || !isImage(asset.getName())) throw new Exception("圖片 assetId 無效");
                JSONObject card = activeDeck.cards.getJSONObject(index);
                card.put("image", assetId).put("imageCaption", clip(caption, 160));
                DeckActivity.showActiveCard();
                return new JSONObject().put("success", true).put("cardId", cardId).put("assetId", assetId).put("message", "已將圖片加入後續卡片");
            } catch (Exception error) { return failure("無法加入圖片：" + error.getMessage()); }
        }
    }

    static JSONObject updateFutureCard(String cardId, JSONObject patch) {
        synchronized (LOCK) {
            try {
                int index = requireFutureCard(cardId); if (patch == null) throw new Exception("沒有可更新的內容");
                JSONObject card = activeDeck.cards.getJSONObject(index);
                if (patch.has("title")) card.put("title", clip(patch.optString("title"), 100));
                if (patch.has("subtitle")) card.put("subtitle", clip(patch.optString("subtitle"), 180));
                if (patch.has("body")) card.put("body", clip(patch.optString("body"), 1200));
                if (patch.has("speakerNotes") || patch.has("speaker_notes")) card.put("speakerNotes", clip(patch.optString("speakerNotes", patch.optString("speaker_notes")), 700));
                if (patch.has("facts")) card.put("facts", normalizeStrings(patch.optJSONArray("facts"), 8, 140));
                if (patch.has("items")) card.put("items", normalizeStrings(patch.optJSONArray("items"), 8, 180));
                if (patch.has("metrics")) card.put("metrics", normalizeMetrics(patch.optJSONArray("metrics")));
                DeckActivity.showActiveCard();
                return new JSONObject().put("success", true).put("cardId", cardId).put("message", "已更新後續卡片；目前頁保持不變");
            } catch (Exception error) { return failure("無法更新卡片：" + error.getMessage()); }
        }
    }

    static JSONObject insertFutureCard(String afterCardId, JSONObject input) {
        synchronized (LOCK) {
            try {
                if (activeDeck == null || input == null) throw new Exception("尚未開啟 Deck 或卡片內容缺失");
                int after = findCard(afterCardId); if (after < activeIndex) throw new Exception("只能在目前頁或後續位置插入卡片");
                JSONArray changed = new JSONArray();
                for (int i = 0; i <= after; i++) changed.put(activeDeck.cards.getJSONObject(i));
                changed.put(normalizeInsertedCard(input));
                for (int i = after + 1; i < activeDeck.cards.length(); i++) changed.put(activeDeck.cards.getJSONObject(i));
                reindexCards(changed); activeDeck.cards = changed; DeckActivity.showActiveCard();
                return new JSONObject().put("success", true).put("totalCards", changed.length()).put("message", "已在後續流程插入補充卡片");
            } catch (Exception error) { return failure("無法插入卡片：" + error.getMessage()); }
        }
    }

    static JSONObject removeFutureCard(String cardId) {
        synchronized (LOCK) {
            try {
                int remove = requireFutureCard(cardId); if (activeDeck.cards.length() <= 1) throw new Exception("Deck 至少保留一張卡片");
                JSONArray changed = new JSONArray();
                for (int i = 0; i < activeDeck.cards.length(); i++) if (i != remove) changed.put(activeDeck.cards.getJSONObject(i));
                reindexCards(changed); activeDeck.cards = changed; DeckActivity.showActiveCard();
                return new JSONObject().put("success", true).put("totalCards", changed.length()).put("message", "已移除尚未播報的卡片");
            } catch (Exception error) { return failure("無法移除卡片：" + error.getMessage()); }
        }
    }

    private static int requireFutureCard(String cardId) throws Exception {
        if (activeDeck == null) throw new Exception("尚未開啟 Deck");
        int index = findCard(cardId); if (index <= activeIndex) throw new Exception("目前頁與已播報卡片已鎖定；只能修改後續內容");
        return index;
    }
    private static JSONObject normalizeInsertedCard(JSONObject input) throws Exception {
        String type = input.optString("type", "content"); if (!("content".equals(type) || "metric".equals(type) || "timeline".equals(type) || "compare".equals(type))) type = "content";
        JSONObject card = new JSONObject().put("type", type).put("title", clip(input.optString("title", "補充內容"), 100))
                .put("subtitle", clip(input.optString("subtitle"), 180)).put("body", clip(input.optString("body"), 1200))
                .put("speakerNotes", clip(input.optString("speakerNotes", input.optString("speaker_notes")), 700))
                .put("facts", normalizeStrings(input.optJSONArray("facts"), 8, 140)).put("items", normalizeStrings(input.optJSONArray("items"), 8, 180));
        if ("metric".equals(type)) card.put("metrics", normalizeMetrics(input.optJSONArray("metrics")));
        return card;
    }
    private static void reindexCards(JSONArray cards) throws Exception {
        for (int i = 0; i < cards.length(); i++) { JSONObject card = cards.getJSONObject(i); card.put("id", "card-" + (i + 1)); JSONArray next = new JSONArray(); if (i + 1 < cards.length()) next.put("card-" + (i + 2)); card.put("next", next); }
    }
    private static boolean isImage(String name) { String lower = name == null ? "" : name.toLowerCase(); return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp"); }
    private static void collectImages(File root, File dir, JSONArray out) {
        File[] children = dir.listFiles(); if (children == null || out.length() >= 30) return;
        for (File child : children) { if (out.length() >= 30) return; if (child.isDirectory()) collectImages(root, child, out); else if (isImage(child.getName())) try { out.put(new JSONObject().put("assetId", root.toURI().relativize(child.toURI()).getPath()).put("name", child.getName())); } catch (Exception ignored) {} }
    }

    static void userNavigate(int delta) {
        synchronized (LOCK) {
            if (activeDeck == null) return;
            activeIndex = Math.max(0, Math.min(activeDeck.cards.length() - 1, activeIndex + delta));
            DeckActivity.showActiveCard();
        }
    }

    static Deck activeDeck() { synchronized (LOCK) { return activeDeck; } }
    static int activeIndex() { synchronized (LOCK) { return activeIndex; } }

    /** Direct UI entry opens the bundled example without requiring a voice tool call first. */
    static void ensureActiveDeck() {
        synchronized (LOCK) {
            if (activeDeck != null) return;
            try { activeDeck = loadDeck("welcome-deck"); activeIndex = 0; }
            catch (Exception error) { Log.w(TAG, "無法載入範例 Deck：" + error.getMessage()); }
        }
    }

    private static int findCard(String id) {
        if (id == null) return -1;
        for (int i = 0; i < activeDeck.cards.length(); i++) if (id.equals(activeDeck.cards.optJSONObject(i).optString("id"))) return i;
        return -1;
    }

    private static Deck loadDeck(String id) throws Exception {
        if (appContext == null) throw new Exception("Deck 尚未初始化");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) throw new Exception("Deck ID 格式不合法");
        File json = new File(new File(root(), id), "deck.json").getCanonicalFile();
        File allowed = root().getCanonicalFile();
        if (!json.getPath().startsWith(allowed.getPath() + File.separator) || !json.isFile()) throw new Exception("找不到 Deck：" + id);
        Deck deck = readDeck(json);
        if (!id.equals(deck.id) || deck.cards.length() == 0) throw new Exception("Deck 資料不完整");
        return deck;
    }

    private static Deck readDeck(File json) throws Exception {
        FileInputStream input = new FileInputStream(json); ByteArrayOutputStreamEx bytes = new ByteArrayOutputStreamEx();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) > 0) bytes.write(buffer, 0, count);
        input.close();
        return new Deck(new JSONObject(new String(bytes.toByteArray(), Charset.forName("UTF-8"))), json.getParentFile());
    }

    private static JSONObject cardResult(JSONObject card, int cardIndex, boolean current) throws Exception {
        JSONArray facts = card.optJSONArray("facts"); JSONArray next = card.optJSONArray("next");
        JSONObject result = new JSONObject().put("success", true).put("deckId", activeDeck.id).put("deckTitle", activeDeck.title)
                .put("cardId", card.optString("id")).put("cardNumber", cardIndex + 1).put("totalCards", activeDeck.cards.length())
                .put("type", card.optString("type", "content")).put("title", card.optString("title"))
                .put("body", card.optString("body")).put("speakerNotes", card.optString("speakerNotes"))
                .put("facts", facts == null ? new JSONArray() : facts).put("allowedNext", next == null ? new JSONArray() : next);
        if (current) result.put("displayed", true);
        return result;
    }

    private static JSONObject failure(String message) { try { return new JSONObject().put("success", false).put("error", message); } catch (Exception ignored) { return new JSONObject(); } }
    private static void launchDeckActivity() {
        if (appContext == null) return;
        try { appContext.startActivity(new Intent(appContext, DeckActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
        catch (Exception error) { Log.w(TAG, "無法顯示 Deck：" + error.getMessage()); }
    }

    /** Avoid another dependency just for a byte array stream on API 24. */
    private static final class ByteArrayOutputStreamEx extends java.io.ByteArrayOutputStream {}
}
