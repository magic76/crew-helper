# Crew Helper 專案規範

## APK 交付

- 每次成功編譯並完成 APK 簽名驗證後，預設將最終 APK 複製到：
  `/data/data/com.termux/files/home/storage/downloads/CrewHelper-v<versionName>.apk`
- Downloads 交付檔名必須包含版本，例如：`CrewHelper-v1.8.23.apk`。
- 當使用者要求安裝、更新或測試 APK 時，一律執行 `~/install-apk.sh <apk路徑>`。APK 安裝與偵錯嚴格使用無線偵錯 (ADB) 進行背景靜默安裝與即時 logcat 偵錯。若 `~/install-apk.sh` 執行失敗（ADB 離線/未設定），請立即回報使用者無線偵錯未開啟或 Port 已更換，並請使用者於開發者選項開啟無線偵錯並提供最新 Port（或執行 `~/set-adb.sh <port>`）。
