package com.crewpocket.helper;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

/**
 * Owns Deck session state, Deck tool execution and Runtime-owned auto-advance.
 *
 * Gemini transport and model-turn truth stay in NativeGeminiLiveClient. This
 * controller only receives explicit lifecycle signals from the client.
 */
final class DeckRuntimeController {
    interface Host {
        boolean isRunning();
        boolean hasLiveSession();
        boolean isInterruptedCurrentTurn();
        boolean isAgentMuted();
        long lastPlaybackActiveAt();
        void resetModelTurnState();
        void sendInternalDirective(String text);
        void reportStage(String text);
    }

    private static final String TAG = "CrewNativeLive";

    private final Host host;
    private final Handler advanceHandler = new Handler(Looper.getMainLooper());
    private volatile boolean autoAdvanceActive;
    private volatile String startupMode = "";
    private volatile String startupDeckId = "";
    private volatile String startupWorkspaceId = "";
    private volatile boolean startupDispatched;
    private volatile int advanceExpectedIndex = -1;

    private final Runnable advanceRunnable = new Runnable() {
        @Override public void run() {
            triggerAutoAdvance();
        }
    };

    DeckRuntimeController(Host host) {
        if (host == null) throw new IllegalArgumentException("host required");
        this.host = host;
    }

    boolean isAutoAdvanceActive() {
        return autoAdvanceActive;
    }

    void setAutoAdvanceActive(boolean active) {
        autoAdvanceActive = active;
        if (!active) cancelAutoAdvance();
    }

    void configureStartup(String mode, String deckId) {
        configureStartup(mode, deckId, "");
    }

    void configureStartup(String mode, String deckId, String workspaceId) {
        String normalized = mode == null ? "" : mode.trim();
        if (!"create".equals(normalized) && !"present".equals(normalized)) {
            normalized = "";
        }
        startupMode = normalized;
        startupDeckId = deckId == null ? "" : deckId.trim();
        startupWorkspaceId = workspaceId == null ? "" : workspaceId.trim();
        startupDispatched = false;
    }

    String startupMode() {
        return startupMode;
    }

    String startupDeckId() {
        return startupDeckId;
    }

    boolean hasStartupMode() {
        return startupMode != null && !startupMode.isEmpty();
    }

    boolean isCreateStartup() {
        return "create".equals(startupMode);
    }

    boolean isPresentStartup() {
        return "present".equals(startupMode);
    }

    boolean isSessionMode() {
        return isCreateStartup()
                || isPresentStartup()
                || DeckRepository.hasActiveDeck();
    }

    boolean hasActiveDeck() {
        return DeckRepository.hasActiveDeck();
    }

    int activeIndex() {
        return DeckRepository.activeIndex();
    }

    boolean handles(String name) {
        return "list_decks".equals(name)
                || "open_deck".equals(name)
                || "get_deck_card".equals(name)
                || "present_deck_card".equals(name)
                || "advance_deck".equals(name)
                || "create_ephemeral_deck".equals(name)
                || "list_deck_workspace_sources".equals(name)
                || "read_deck_workspace_source".equals(name)
                || "list_deck_images".equals(name)
                || "attach_deck_image".equals(name)
                || "update_deck_card".equals(name)
                || "insert_deck_card".equals(name)
                || "remove_future_deck_card".equals(name);
    }

    JSONObject execute(String name, JSONObject args) throws Exception {
        JSONObject safeArgs = args == null ? new JSONObject() : args;

        if ("list_decks".equals(name)) {
            return DeckRepository.listDecks();
        }
        if ("open_deck".equals(name)) {
            JSONObject result =
                    DeckRepository.openDeck(safeArgs.optString("deck_id"));
            if (result.optBoolean("success", false)) {
                autoAdvanceActive = true;
            }
            return result;
        }
        if ("get_deck_card".equals(name)) {
            return DeckRepository.getCard(safeArgs.optString("card_id"));
        }
        if ("present_deck_card".equals(name)) {
            JSONObject result =
                    DeckRepository.presentCard(safeArgs.optString("card_id"));
            if (result.optBoolean("success", false)) {
                autoAdvanceActive = true;
            }
            return result;
        }
        if ("advance_deck".equals(name)) {
            if (autoAdvanceActive) {
                return new JSONObject()
                        .put("success", true)
                        .put("noOp", true)
                        .put("runtimeOwned", true)
                        .put("currentIndex", DeckRepository.activeIndex())
                        .put(
                                "instruction",
                                "自動簡報翻頁由 Runtime 控制。不要再次呼叫 advance_deck；"
                                        + "請只講解目前顯示的卡片，Runtime 會在音訊播放完畢後翻頁。");
            }
            JSONObject result = DeckRepository.advance();
            if (result.optBoolean("success", false)) {
                autoAdvanceActive = true;
            }
            return result;
        }
        if ("list_deck_workspace_sources".equals(name)) {
            if (startupWorkspaceId.isEmpty()
                    || !DeckWorkspaceRepository.hasWorkspace(startupWorkspaceId)) {
                return new JSONObject()
                        .put("success", false)
                        .put("error", "這次 AI 建立簡報沒有指定 Deck Workspace");
            }
            return DeckWorkspaceRepository.listSources();
        }
        if ("read_deck_workspace_source".equals(name)) {
            if (startupWorkspaceId.isEmpty()
                    || !DeckWorkspaceRepository.hasWorkspace(startupWorkspaceId)) {
                return new JSONObject()
                        .put("success", false)
                        .put("error", "這次 AI 建立簡報沒有指定 Deck Workspace");
            }
            return DeckWorkspaceRepository.readSource(
                    safeArgs.optString("source_id"));
        }
        if ("create_ephemeral_deck".equals(name)) {
            java.io.File assetDirectory =
                    startupWorkspaceId.isEmpty()
                            ? null
                            : DeckWorkspaceRepository.workspaceFilesDirectory(
                                    startupWorkspaceId);
            JSONObject result = DeckRepository.createEphemeralDeck(
                    safeArgs.optString("title"),
                    safeArgs.optJSONArray("cards"),
                    assetDirectory);
            if (result.optBoolean("success", false)) {
                autoAdvanceActive = true;
            }
            return result;
        }
        if ("list_deck_images".equals(name)) {
            return DeckRepository.listDeckImages();
        }
        if ("attach_deck_image".equals(name)) {
            return DeckRepository.attachImageToFutureCard(
                    safeArgs.optString("card_id"),
                    safeArgs.optString("asset_id"),
                    safeArgs.optString("caption"));
        }
        if ("update_deck_card".equals(name)) {
            return DeckRepository.updateFutureCard(
                    safeArgs.optString("card_id"),
                    safeArgs.optJSONObject("patch"));
        }
        if ("insert_deck_card".equals(name)) {
            return DeckRepository.insertFutureCard(
                    safeArgs.optString("after_card_id"),
                    safeArgs.optJSONObject("card"));
        }
        if ("remove_future_deck_card".equals(name)) {
            return DeckRepository.removeFutureCard(
                    safeArgs.optString("card_id"));
        }

        return new JSONObject()
                .put("success", false)
                .put("error", "不支援的 Deck 工具：" + name);
    }

    void dispatchStartupIfNeeded(boolean setupReady) {
        if (!setupReady || startupDispatched || startupMode.isEmpty()) return;

        if (isCreateStartup()) {
            startupDispatched = true;
            if (!startupWorkspaceId.isEmpty()
                    && DeckWorkspaceRepository.hasWorkspace(startupWorkspaceId)) {
                JSONObject workspace = DeckWorkspaceRepository.getWorkspaceSummary();
                host.sendInternalDirective(
                        "【Deck Workspace 建立入口】使用者已主動指定一個『簡報資料來源』資料夾。"
                                + "Workspace 摘要：" + workspace.toString()
                                + "。先呼叫 list_deck_workspace_sources，看有哪些來源；"
                                + "再只讀與主題最相關的來源，不要把所有檔案全部讀完。"
                                + "如果使用者尚未說明簡報目的/主題，可用一句話詢問。"
                                + "掌握資料後，先用語音提出 4–8 頁的簡短大綱，等待使用者確認或修改；"
                                + "在使用者確認前不要呼叫 create_ephemeral_deck。"
                                + "確認後建立 3–8 頁簡報，每頁的 sources 必須列出實際支援該頁、且你真的讀過的檔案路徑；"
                                + "若 workspace source 有 image assetId，可直接放進 card.image。"
                                + "PDF 或其他 readable=false 的來源只能依檔名/類型判斷，不得猜測檔案內容。"
                                + "建立完成後直接以 Gemini 主講人的身分開始介紹第一頁。");
            } else {
                host.sendInternalDirective(
                        "【AI 簡報建立入口】使用者剛剛主動選擇『AI 建立新簡報』。"
                                + "如果使用者還沒說主題，現在只用一句話問：想做什麼主題的簡報？"
                                + "不要要求 deck.json、檔案、資料夾或任何技術設定。"
                                + "使用者提供主題後，呼叫 create_ephemeral_deck 一次建立 3–8 頁，"
                                + "建立完成後直接以 Gemini 主講人的身分開始介紹第一頁。");
            }
            return;
        }

        if (isPresentStartup()) {
            JSONObject card = DeckRepository.presentCard("");
            if (!card.optBoolean("success", false)) {
                startupDispatched = true;
                host.sendInternalDirective(
                        "【AI 簡報啟動失敗】無法取得目前簡報第一頁。"
                                + "請簡短告訴使用者簡報無法載入，不要猜測內容。");
                return;
            }

            startupDispatched = true;
            autoAdvanceActive = true;
            host.resetModelTurnState();
            host.sendInternalDirective(
                    "【AI 簡報開始】使用者已選好簡報，第一頁現在已顯示。"
                            + "你是這場簡報的主講人。直接自然地開始介紹目前第一頁，"
                            + "不要再問要不要開始、不要呼叫 open_deck/advance_deck。"
                            + "Runtime 會在你的語音真正播放完畢後自動翻頁。"
                            + "目前第一頁完整資料：" + card.toString());
        }
    }

    void onTurnInterrupted() {
        advanceHandler.removeCallbacks(advanceRunnable);
        advanceExpectedIndex = -1;
    }

    void onNarrationTurnComplete(
            boolean narrationTurn,
            boolean turnWasInterrupted) {
        if (!narrationTurn
                || !autoAdvanceActive
                || !DeckRepository.hasActiveDeck()
                || turnWasInterrupted
                || host.isAgentMuted()) {
            return;
        }
        scheduleAutoAdvance();
    }

    void cancelAutoAdvance() {
        advanceHandler.removeCallbacks(advanceRunnable);
        advanceExpectedIndex = -1;
        autoAdvanceActive = false;
    }

    private void scheduleAutoAdvance() {
        if (!host.isRunning()
                || !host.hasLiveSession()
                || !autoAdvanceActive
                || host.isInterruptedCurrentTurn()
                || host.isAgentMuted()) {
            return;
        }
        if (!DeckRepository.hasActiveDeck()) {
            autoAdvanceActive = false;
            advanceExpectedIndex = -1;
            return;
        }

        advanceExpectedIndex = DeckRepository.activeIndex();

        long remaining = Math.max(
                0L,
                host.lastPlaybackActiveAt() - System.currentTimeMillis());
        long delay = remaining + 650L;
        advanceHandler.removeCallbacks(advanceRunnable);
        advanceHandler.postDelayed(advanceRunnable, delay);
        Log.d(
                TAG,
                "0054 排程簡報翻頁：cardIndex="
                        + advanceExpectedIndex + " delay=" + delay + "ms");
    }

    private void triggerAutoAdvance() {
        if (!host.isRunning()
                || !host.hasLiveSession()
                || !autoAdvanceActive
                || host.isInterruptedCurrentTurn()
                || host.isAgentMuted()) {
            return;
        }
        if (!DeckRepository.hasActiveDeck()) {
            autoAdvanceActive = false;
            advanceExpectedIndex = -1;
            return;
        }

        int expectedIndex = advanceExpectedIndex;
        if (expectedIndex < 0) return;

        int actualIndex = DeckRepository.activeIndex();
        if (actualIndex != expectedIndex) {
            Log.w(
                    TAG,
                    "0054 忽略過期簡報翻頁：expected="
                            + expectedIndex + " actual=" + actualIndex);
            advanceExpectedIndex = -1;
            return;
        }

        long remaining =
                host.lastPlaybackActiveAt() - System.currentTimeMillis();
        if (remaining > 100L) {
            advanceHandler.removeCallbacks(advanceRunnable);
            advanceHandler.postDelayed(
                    advanceRunnable, remaining + 450L);
            return;
        }

        if (DeckRepository.hasNext()) {
            JSONObject advanced =
                    DeckRepository.advanceFromIndex(expectedIndex);
            if (!advanced.optBoolean("success", false)) {
                Log.w(
                        TAG,
                        "0054 Runtime 翻頁未執行："
                                + advanced.optString("error", "UNKNOWN"));
                advanceExpectedIndex = -1;
                return;
            }

            int currentIndex = DeckRepository.activeIndex();
            int currentCardNum = currentIndex + 1;
            int total = DeckRepository.totalCards();
            advanceExpectedIndex = -1;

            String cardData = advanced.toString();
            if (cardData.length() > 7000) {
                cardData = cardData.substring(0, 7000);
            }

            Log.d(
                    TAG,
                    "0054 Runtime 已翻至第 "
                            + currentCardNum + "/" + total + " 頁");
            host.reportStage(
                    "簡報導播：進入第 "
                            + currentCardNum + "/" + total + " 頁…");

            host.resetModelTurnState();
            host.sendInternalDirective(
                    "【簡報 Runtime 已翻頁】目前畫面已由 Runtime 切到第 "
                            + currentCardNum + "/" + total + " 頁。"
                            + "以下是目前卡片資料：" + cardData
                            + "。只講解目前這一頁，不要呼叫 advance_deck 或 present_deck_card，"
                            + "不要提前切換畫面。自動翻頁由 Runtime 在這頁語音真正播放完畢後處理。");
            return;
        }

        advanceExpectedIndex = -1;
        autoAdvanceActive = false;
        Log.d(TAG, "0054 自動簡報已抵達最後一張卡片");
        host.reportStage("簡報導播：全部卡片播報完畢，進行總結…");
        host.resetModelTurnState();
        host.sendInternalDirective(
                "【簡報導播系統】目前已在最後一張卡片，所有頁面都已播報完成。"
                        + "請不要再呼叫任何翻頁工具，只做一段簡短總結並作結。");
    }
}
