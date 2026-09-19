package com.crewpocket.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One active folder-backed source workspace for AI Deck creation.
 *
 * The original SAF tree is never exposed to Gemini. Crew copies a bounded set
 * of supported files into app-private storage, builds a compact metadata index,
 * and only returns source text when the model explicitly asks for one source.
 */
final class DeckWorkspaceRepository {
    private static final String ROOT = "deck-workspace";
    private static final String INDEX = "workspace.json";
    private static final String FILES = "files";
    private static final String TEXT = "text";
    private static final String WORKSPACE_ID = "active";
    private static final int MAX_FILES = 160;
    private static final long MAX_TOTAL_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_EXTRACTED_CHARS = 24000;
    private static final int MAX_PREVIEW_CHARS = 420;
    private static final Object LOCK = new Object();

    private static Context appContext;

    private DeckWorkspaceRepository() {}

    static void initialize(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    static JSONObject importWorkspaceTree(Context context, Uri treeUri) {
        if (context == null || treeUri == null) return failure("沒有選擇資料夾");
        initialize(context);
        synchronized (LOCK) {
            File root = root();
            File staging = null;
            try {
                try {
                    context.getContentResolver().takePersistableUriPermission(
                            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}

                staging = new File(context.getFilesDir(), ROOT + ".staging");
                deleteTree(staging);
                if (!staging.mkdirs()) throw new Exception("無法建立 Workspace 暫存資料夾");
                File files = new File(staging, FILES);
                File text = new File(staging, TEXT);
                if (!files.mkdirs() || !text.mkdirs()) throw new Exception("無法建立 Workspace 索引資料夾");

                JSONArray sources = new JSONArray();
                int[] fileCount = new int[]{0};
                int[] skipped = new int[]{0};
                int[] readable = new int[]{0};
                int[] images = new int[]{0};
                long[] totalBytes = new long[]{0L};

                String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
                copyAndIndex(
                        context.getContentResolver(),
                        treeUri,
                        treeDocumentId,
                        "",
                        files,
                        text,
                        sources,
                        fileCount,
                        skipped,
                        readable,
                        images,
                        totalBytes);

                String title = workspaceTitle(treeDocumentId);
                JSONObject index = new JSONObject()
                        .put("workspaceId", WORKSPACE_ID)
                        .put("title", title)
                        .put("treeUri", treeUri.toString())
                        .put("indexedAt", System.currentTimeMillis())
                        .put("files", fileCount[0])
                        .put("readableSources", readable[0])
                        .put("images", images[0])
                        .put("skipped", skipped[0])
                        .put("bytes", totalBytes[0])
                        .put("sources", sources);
                writeUtf8(new File(staging, INDEX), index.toString());

                deleteTree(root);
                if (!staging.renameTo(root)) throw new Exception("無法完成 Workspace 索引");
                staging = null;

                return summary(index)
                        .put("message", "已建立簡報資料來源索引，可開始用這個資料夾產生簡報");
            } catch (Exception error) {
                return failure("資料來源建立失敗：" + safe(error.getMessage()));
            } finally {
                if (staging != null) deleteTree(staging);
            }
        }
    }

    static JSONObject getWorkspaceSummary() {
        synchronized (LOCK) {
            try {
                JSONObject index = readIndex();
                if (index == null) return failure("尚未選擇簡報資料來源");
                return summary(index);
            } catch (Exception error) {
                return failure("無法讀取簡報資料來源：" + safe(error.getMessage()));
            }
        }
    }

    static JSONObject listSources() {
        synchronized (LOCK) {
            try {
                JSONObject index = readIndex();
                if (index == null) return failure("尚未選擇簡報資料來源");
                JSONArray input = index.optJSONArray("sources");
                JSONArray output = new JSONArray();
                if (input != null) {
                    for (int i = 0; i < input.length(); i++) {
                        JSONObject source = input.optJSONObject(i);
                        if (source == null) continue;
                        output.put(new JSONObject()
                                .put("sourceId", source.optString("sourceId"))
                                .put("path", source.optString("path"))
                                .put("kind", source.optString("kind"))
                                .put("bytes", source.optLong("bytes"))
                                .put("readable", source.optBoolean("readable"))
                                .put("preview", source.optString("preview"))
                                .put("assetId", source.optString("assetId")));
                    }
                }
                return summary(index)
                        .put("success", true)
                        .put("sources", output);
            } catch (Exception error) {
                return failure("無法列出資料來源：" + safe(error.getMessage()));
            }
        }
    }

    static JSONObject readSource(String sourceId) {
        synchronized (LOCK) {
            try {
                JSONObject index = readIndex();
                if (index == null) return failure("尚未選擇簡報資料來源");
                String wanted = sourceId == null ? "" : sourceId.trim();
                JSONArray sources = index.optJSONArray("sources");
                JSONObject found = null;
                if (sources != null) {
                    for (int i = 0; i < sources.length(); i++) {
                        JSONObject item = sources.optJSONObject(i);
                        if (item != null && wanted.equals(item.optString("sourceId"))) {
                            found = item;
                            break;
                        }
                    }
                }
                if (found == null) return failure("找不到資料來源：" + wanted);

                JSONObject result = new JSONObject()
                        .put("success", true)
                        .put("sourceId", wanted)
                        .put("path", found.optString("path"))
                        .put("kind", found.optString("kind"))
                        .put("assetId", found.optString("assetId"))
                        .put("readable", found.optBoolean("readable"));

                String textPath = found.optString("textPath");
                if (!textPath.isEmpty()) {
                    File file = new File(root(), textPath).getCanonicalFile();
                    if (!file.getPath().startsWith(root().getCanonicalPath() + File.separator)
                            || !file.isFile()) {
                        return failure("資料來源文字索引遺失：" + wanted);
                    }
                    result.put("text", readUtf8(file, MAX_EXTRACTED_CHARS));
                } else {
                    result.put("text", "");
                    result.put(
                            "note",
                            "這是二進位或目前無法抽文字的來源；可使用檔名、類型與圖片 assetId 規劃簡報，但不要猜測未讀到的內容。");
                }
                return result;
            } catch (Exception error) {
                return failure("無法讀取資料來源：" + safe(error.getMessage()));
            }
        }
    }

    static File workspaceFilesDirectory(String workspaceId) {
        synchronized (LOCK) {
            try {
                JSONObject index = readIndex();
                if (index == null
                        || !WORKSPACE_ID.equals(workspaceId)
                        || !WORKSPACE_ID.equals(index.optString("workspaceId"))) {
                    return null;
                }
                File files = new File(root(), FILES);
                return files.isDirectory() ? files : null;
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    static boolean hasWorkspace(String workspaceId) {
        return workspaceFilesDirectory(workspaceId) != null;
    }

    private static void copyAndIndex(
            ContentResolver resolver,
            Uri treeUri,
            String parentId,
            String relativePrefix,
            File filesDir,
            File textDir,
            JSONArray sources,
            int[] fileCount,
            int[] skipped,
            int[] readable,
            int[] images,
            long[] totalBytes) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        Cursor cursor = resolver.query(
                children,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                },
                null,
                null,
                null);
        if (cursor == null) throw new Exception("無法讀取資料夾");

        try {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long declaredSize = cursor.isNull(3) ? 0L : Math.max(0L, cursor.getLong(3));
                if (name == null || name.trim().isEmpty()) continue;

                String path = relativePrefix.isEmpty()
                        ? name
                        : relativePrefix + "/" + name;

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    copyAndIndex(
                            resolver,
                            treeUri,
                            id,
                            path,
                            filesDir,
                            textDir,
                            sources,
                            fileCount,
                            skipped,
                            readable,
                            images,
                            totalBytes);
                    continue;
                }

                String ext = extension(name);
                String kind = kindFor(ext);
                if ("unsupported".equals(kind)) {
                    skipped[0]++;
                    continue;
                }
                if (fileCount[0] >= MAX_FILES) throw new Exception("Workspace 最多索引 " + MAX_FILES + " 個支援檔案");
                if (declaredSize > MAX_TOTAL_BYTES) {
                    skipped[0]++;
                    continue;
                }

                fileCount[0]++;
                String sourceId = String.format(Locale.US, "source-%03d", fileCount[0]);
                String storedName = sourceId + (ext.isEmpty() ? "" : "." + ext);
                File stored = new File(filesDir, storedName);
                Uri child = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                InputStream in = resolver.openInputStream(child);
                if (in == null) throw new Exception("無法讀取檔案：" + name);
                long copied = copyBounded(in, stored, totalBytes);
                in.close();

                String extracted = extractText(stored, ext);
                String textPath = "";
                String preview = "";
                boolean canRead = extracted != null && !extracted.trim().isEmpty();
                if (canRead) {
                    extracted = normalizeText(extracted, MAX_EXTRACTED_CHARS);
                    File extractedFile = new File(textDir, sourceId + ".txt");
                    writeUtf8(extractedFile, extracted);
                    textPath = TEXT + "/" + extractedFile.getName();
                    preview = normalizeText(extracted, MAX_PREVIEW_CHARS);
                    readable[0]++;
                }

                String assetId = "";
                if ("image".equals(kind)) {
                    assetId = storedName;
                    images[0]++;
                }

                sources.put(new JSONObject()
                        .put("sourceId", sourceId)
                        .put("path", path)
                        .put("kind", kind)
                        .put("bytes", copied)
                        .put("readable", canRead)
                        .put("preview", preview)
                        .put("textPath", textPath)
                        .put("assetId", assetId));
            }
        } finally {
            cursor.close();
        }
    }

    private static long copyBounded(InputStream in, File out, long[] totalBytes) throws Exception {
        FileOutputStream fos = new FileOutputStream(out);
        byte[] buffer = new byte[8192];
        int count;
        long copied = 0L;
        try {
            while ((count = in.read(buffer)) > 0) {
                copied += count;
                totalBytes[0] += count;
                if (totalBytes[0] > MAX_TOTAL_BYTES) throw new Exception("Workspace 最大 50 MB");
                fos.write(buffer, 0, count);
            }
        } finally {
            fos.close();
        }
        return copied;
    }

    private static String extractText(File file, String ext) {
        try {
            if (isPlainText(ext)) return readUtf8(file, MAX_EXTRACTED_CHARS);
            if ("docx".equals(ext) || "pptx".equals(ext) || "xlsx".equals(ext)) {
                return extractOfficeText(file, ext);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String extractOfficeText(File file, String ext) throws Exception {
        ZipInputStream zip = new ZipInputStream(new FileInputStream(file));
        StringBuilder out = new StringBuilder();
        byte[] buffer = new byte[4096];
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && out.length() < MAX_EXTRACTED_CHARS) {
                String name = entry.getName();
                boolean wanted =
                        ("docx".equals(ext)
                                && (name.equals("word/document.xml")
                                    || name.startsWith("word/header")
                                    || name.startsWith("word/footer")))
                        || ("pptx".equals(ext)
                                && (name.startsWith("ppt/slides/slide")
                                    || name.startsWith("ppt/notesSlides/notesSlide")))
                        || ("xlsx".equals(ext)
                                && (name.equals("xl/sharedStrings.xml")
                                    || name.startsWith("xl/worksheets/sheet")));
                if (!wanted || entry.isDirectory()) continue;

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int count;
                while ((count = zip.read(buffer)) > 0
                        && bytes.size() < 512 * 1024
                        && out.length() < MAX_EXTRACTED_CHARS) {
                    bytes.write(buffer, 0, count);
                }
                String xml = new String(bytes.toByteArray(), Charset.forName("UTF-8"));
                String plain = xml
                        .replaceAll("<[^>]+>", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&apos;", "'");
                out.append(' ').append(plain);
            }
        } finally {
            zip.close();
        }
        return normalizeText(out.toString(), MAX_EXTRACTED_CHARS);
    }

    private static boolean isPlainText(String ext) {
        return "txt".equals(ext)
                || "md".equals(ext)
                || "csv".equals(ext)
                || "json".equals(ext)
                || "xml".equals(ext)
                || "html".equals(ext)
                || "htm".equals(ext)
                || "log".equals(ext)
                || "java".equals(ext)
                || "kt".equals(ext)
                || "js".equals(ext)
                || "ts".equals(ext)
                || "tsx".equals(ext)
                || "jsx".equals(ext)
                || "py".equals(ext)
                || "yaml".equals(ext)
                || "yml".equals(ext);
    }

    private static String kindFor(String ext) {
        if (isPlainText(ext)) return "text";
        if ("docx".equals(ext)) return "document";
        if ("pptx".equals(ext)) return "presentation";
        if ("xlsx".equals(ext)) return "spreadsheet";
        if ("pdf".equals(ext)) return "pdf";
        if ("png".equals(ext)
                || "jpg".equals(ext)
                || "jpeg".equals(ext)
                || "webp".equals(ext)
                || "gif".equals(ext)) return "image";
        return "unsupported";
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 || dot >= name.length() - 1
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static JSONObject summary(JSONObject index) throws Exception {
        return new JSONObject()
                .put("success", true)
                .put("workspaceId", index.optString("workspaceId"))
                .put("title", index.optString("title"))
                .put("files", index.optInt("files"))
                .put("readableSources", index.optInt("readableSources"))
                .put("images", index.optInt("images"))
                .put("skipped", index.optInt("skipped"))
                .put("bytes", index.optLong("bytes"))
                .put("indexedAt", index.optLong("indexedAt"));
    }

    private static JSONObject readIndex() throws Exception {
        File file = new File(root(), INDEX);
        if (!file.isFile()) return null;
        return new JSONObject(readUtf8(file, 2_000_000));
    }

    private static File root() {
        return appContext == null ? null : new File(appContext.getFilesDir(), ROOT);
    }

    private static String workspaceTitle(String treeDocumentId) {
        String value = treeDocumentId == null ? "" : treeDocumentId.trim();
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon < value.length() - 1) value = value.substring(colon + 1);
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash < value.length() - 1) value = value.substring(slash + 1);
        return value.isEmpty() ? "簡報資料來源" : value;
    }

    private static String normalizeText(String value, int max) {
        String text = value == null ? "" : value
                .replace((char) 0, ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" +", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.length() > max) text = text.substring(0, max);
        return text;
    }

    private static String readUtf8(File file, int maxChars) throws Exception {
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        try {
            while ((count = input.read(buffer)) > 0 && bytes.size() < maxChars * 4) {
                bytes.write(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        String value = new String(bytes.toByteArray(), Charset.forName("UTF-8"));
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }

    private static void writeUtf8(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("無法建立資料夾");
        }
        FileOutputStream output = new FileOutputStream(file);
        try {
            output.write((value == null ? "" : value).getBytes(Charset.forName("UTF-8")));
        } finally {
            output.close();
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] entries = file.listFiles();
            if (entries != null) {
                for (File entry : entries) deleteTree(entry);
            }
        }
        file.delete();
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }

    private static JSONObject failure(String message) {
        try {
            return new JSONObject().put("success", false).put("error", message);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}
