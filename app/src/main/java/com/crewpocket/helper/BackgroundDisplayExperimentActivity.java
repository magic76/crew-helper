package com.crewpocket.helper;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Base64;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manual test UI only. Never routes tool calls or fallbacks to the main display.
 * The helper is not started automatically: user must explicitly authorize
 * Shizuku UserService or start it through ADB shell.
 */
public final class BackgroundDisplayExperimentActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private BackgroundShizukuController shizuku;
    private TextView status;
    private EditText packageField;
    private ImageView preview;
    private Bitmap previewBitmap;
    private boolean busy;
    private int screenWidth, screenHeight;
    private float pressX, pressY;
    private long pressAt;
    private String lastDiagnostics = "尚未檢查 Helper";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(layout);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("背景螢幕實驗");
        title.setTextSize(23);
        title.setTextColor(Color.WHITE);
        layout.addView(title);

        TextView info = new TextView(this);
        info.setText("背景 Shell Helper 可由 Shizuku 一鍵啟動，或保留原本 ADB 啟動方式。"
                + "Shizuku 需先安裝、啟動並授權 Crew Helper；啟動後切換到行動網路可繼續使用，"
                + "重新開機後無 Root 的 Shizuku 仍須重新啟動。");
        info.setTextSize(14);
        info.setPadding(0, dp(12), 0, dp(12));
        layout.addView(info);

        CheckBox liveMode = new CheckBox(this);
        liveMode.setText("啟用 Gemini Live 背景操作（含背景畫面辨識）");
        liveMode.setChecked(AppConfig.isBackgroundLiveEnabled(this));
        liveMode.setOnCheckedChangeListener((button, enabled) ->
                AppConfig.setBackgroundLiveEnabled(this, enabled));
        layout.addView(liveMode);

        TextView visionPrivacy = new TextView(this);
        visionPrivacy.setText("啟用後，當背景語意辨識不足或你要求查看畫面時，"
                + "背景螢幕影像可能送往 Gemini 分析。"
                + "請勿在登入、付款、驗證碼或其他私密畫面測試；背景影像不會另存成檔案。"
                + "變更設定後需重新開始 Live。");
        visionPrivacy.setTextSize(12);
        visionPrivacy.setPadding(0, dp(4), 0, dp(14));
        layout.addView(visionPrivacy);

        status = new TextView(this);
        status.setText("尚未檢查 Helper");
        status.setPadding(0, dp(8), 0, dp(12));
        layout.addView(status);

        shizuku = new BackgroundShizukuController(this, worker, (message, finished) -> {
            status.setText(message);
            if (finished) {
                busy = false;
                lastDiagnostics = "Crew Helper BG Shizuku"
                        + " | result=" + message.replaceAll("[^A-Za-z0-9_ .:-]", "");
            }
        });
        addButton(layout, "查看 Shizuku 服務狀態", () ->
                status.setText(shizuku.status()));
        addButton(layout, "透過 Shizuku 啟動背景 Helper", () -> {
            if (busy) return;
            busy = true;
            shizuku.start();
        });
        addButton(layout, "安裝／開啟 Shizuku", () -> {
            try {
                android.content.Intent launch = getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (launch != null) {
                    startActivity(launch);
                    return;
                }
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://shizuku.rikka.app/download/")));
            } catch (Exception failure) {
                status.setText("無法開啟 Shizuku，請手動安裝。");
            }
        });

        packageField = new EditText(this);
        packageField.setSingleLine(true);
        packageField.setHint("App package");
        packageField.setText("com.apple.android.music");
        layout.addView(packageField);
        addButton(layout, "檢查 Helper", () -> run("status", null));
        // The app cannot grant itself shell UID or silently turn on ADB.
        // Guide users to Android's own settings; pairing stays user-authorized.
        addButton(layout, "開啟開發人員選項（無線偵錯）", () -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception missing) {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_SETTINGS));
            }
        });
        addButton(layout, "複製不含畫面內容的診斷摘要", () -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Crew Background Diagnostic", lastDiagnostics));
            android.widget.Toast.makeText(this, "已複製背景診斷摘要", android.widget.Toast.LENGTH_SHORT).show();
        });
        addButton(layout, "背景開啟 App", () -> {
            try {
                run("open", new JSONObject().put(
                        "package", packageField.getText().toString().trim()));
            } catch (Exception ignored) {}
        });
        addButton(layout, "重新讀取背景畫面", this::refreshFrame);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));
        preview.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                pressX = event.getX();
                pressY = event.getY();
                pressAt = event.getEventTime();
                return true;
            }
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            int[] from = pointOnDisplay(pressX, pressY);
            int[] to = pointOnDisplay(event.getX(), event.getY());
            if (from == null || to == null) return true;
            try {
                boolean swipe = Math.hypot(event.getX() - pressX, event.getY() - pressY) > dp(24);
                JSONObject gesture = new JSONObject();
                if (swipe) {
                    gesture.put("x1", from[0]).put("y1", from[1])
                            .put("x2", to[0]).put("y2", to[1])
                            .put("durationMs", (int) Math.max(100, Math.min(3000,
                                    event.getEventTime() - pressAt)));
                } else {
                    gesture.put("x", to[0]).put("y", to[1]);
                }
                run(swipe ? "swipe" : "tap", gesture);
            } catch (Exception ignored) {}
            return true;
        });

        addButton(layout, "移到主螢幕（人工接管）", () -> run("front", null));
        addButton(layout, "關閉背景螢幕", () -> run("close", null));
        run("status", null);
    }

    private int[] pointOnDisplay(float x, float y) {
        if (previewBitmap == null || screenWidth <= 0 || screenHeight <= 0) return null;
        RectF bounds = new RectF(0, 0, previewBitmap.getWidth(), previewBitmap.getHeight());
        Matrix matrix = preview.getImageMatrix();
        matrix.mapRect(bounds);
        bounds.offset(preview.getPaddingLeft(), preview.getPaddingTop());
        if (!bounds.contains(x, y) || bounds.width() <= 0 || bounds.height() <= 0)
            return null;
        return new int[]{
            Math.min(screenWidth - 1, (int) ((x - bounds.left) / bounds.width() * screenWidth)),
            Math.min(screenHeight - 1, (int) ((y - bounds.top) / bounds.height() * screenHeight))
        };
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addButton(LinearLayout layout, String text, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        layout.addView(button);
    }

    private void run(String op, JSONObject payload) {
        if (busy) return;
        busy = true;
        status.setText("執行 " + op + " …");
        worker.execute(() -> {
            JSONObject request = payload == null ? new JSONObject() : payload;
            JSONObject reply;
            try {
                request.put("op", op);
                reply = BackgroundDisplayClient.request(request);
            } catch (Exception error) {
                reply = new JSONObject();
            }
            final JSONObject result = reply;
            runOnUiThread(() -> {
                busy = false;
                lastDiagnostics = "Crew Helper BG"
                        + " | op=" + op
                        + " | success=" + result.optBoolean("success", false)
                        + " | error=" + result.optString("error", "NONE")
                        + " | protocol=" + result.optInt("protocolVersion", -1)
                        + " | transport=" + result.optString("transport", "DIRECT_SOCKET")
                        + " | active=" + result.optBoolean("active", false)
                        + " | displayId=" + result.optInt("displayId", -1)
                        + " | focusIsolated=" + result.optBoolean("focusIsolated", false);
                if (result.optBoolean("success", false)) {
                    status.setText("成功 · " + result.optString("package", op)
                            + " · Display " + result.optInt("displayId", -1)
                            + " · " + result.optString("transport", "DIRECT_SOCKET")
                            + ("status".equals(op) ? (result.optBoolean("focusIsolated", false)
                                    ? " · 獨立焦點" : " · 共用焦點／未確認") : ""));
                    if ("front".equals(op) || "close".equals(op)) clearFrame();
                } else {
                    String code = result.optString("error", "UNKNOWN");
                    String next = "HELPER_RESTART_REQUIRED".equals(code)
                            ? "（請先停止舊版 Helper，再從 Shizuku 啟動）"
                            : (code.startsWith("DIRECT_SHELL_BINDER_")
                                || code.startsWith("DIRECT_SHELL_REPLY_"))
                                ? "（Direct Binder 未就緒／已中斷；請按 Shizuku 啟動背景 Helper）"
                                : "";
                    status.setText("操作失敗：" + code + next);
                }
            });
        });
    }

    private void refreshFrame() {
        if (busy) return;
        busy = true;
        status.setText("讀取背景畫面…");
        worker.execute(() -> {
            JSONObject reply = BackgroundDisplayClient.command("preview");
            Bitmap bitmap = null;
            if (reply.optBoolean("success", false)) {
                try {
                    byte[] data = Base64.decode(reply.getString("jpegBase64"), Base64.DEFAULT);
                    bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                } catch (Exception ignored) {}
            }
            final Bitmap shot = bitmap;
            runOnUiThread(() -> {
                busy = false;
                if (shot == null) {
                    status.setText("無畫面：" + reply.optString("error", "DECODE_FAILED"));
                    return;
                }
                clearFrame();
                previewBitmap = shot;
                preview.setImageBitmap(shot);
                screenWidth = reply.optInt("width", shot.getWidth());
                screenHeight = reply.optInt("height", shot.getHeight());
                status.setText("背景畫面 " + screenWidth + " × " + screenHeight);
            });
        });
    }

    private void clearFrame() {
        if (preview != null) preview.setImageDrawable(null);
        if (previewBitmap != null) previewBitmap.recycle();
        previewBitmap = null;
        screenWidth = 0;
        screenHeight = 0;
    }

    @Override protected void onDestroy() {
        if (shizuku != null) shizuku.destroy();
        clearFrame();
        worker.shutdownNow();
        super.onDestroy();
    }
}
