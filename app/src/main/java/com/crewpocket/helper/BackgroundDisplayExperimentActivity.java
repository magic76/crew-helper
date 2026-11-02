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
 * The helper is not started automatically: user must authorize an ADB shell first.
 */
public final class BackgroundDisplayExperimentActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText packageField;
    private ImageView preview;
    private Bitmap previewBitmap;
    private boolean busy;
    private int screenWidth, screenHeight;
    private float pressX, pressY;
    private long pressAt;

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
        info.setText("需先透過 ADB 啟動 Shell Helper。此頁僅供手動測試，Gemini Live 操作維持原樣。");
        info.setTextSize(14);
        info.setPadding(0, dp(12), 0, dp(12));
        layout.addView(info);

        status = new TextView(this);
        status.setText("尚未檢查 Helper");
        status.setPadding(0, dp(8), 0, dp(12));
        layout.addView(status);

        packageField = new EditText(this);
        packageField.setSingleLine(true);
        packageField.setHint("App package");
        packageField.setText("com.apple.android.music");
        layout.addView(packageField);
        addButton(layout, "檢查 Helper", () -> run("status", null));
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
                if (result.optBoolean("success", false)) {
                    status.setText("成功 · " + result.optString("package", op)
                            + " · Display " + result.optInt("displayId", -1));
                    if ("front".equals(op) || "close".equals(op)) clearFrame();
                } else {
                    status.setText("操作失敗：" + result.optString("error", "UNKNOWN"));
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
        clearFrame();
        worker.shutdownNow();
        super.onDestroy();
    }
}
