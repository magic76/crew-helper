# Crew Helper 專案規範

## APK 交付

- 每次成功編譯並完成 APK 簽名驗證後，預設將最終 APK 複製到：
  `/data/data/com.termux/files/home/storage/downloads/CrewHelper-v<versionName>.apk`
- Downloads 交付檔名必須包含版本，例如：`CrewHelper-v1.8.23.apk`。
- 專案內的 `CrewHelper.apk` 保留為建置來源；Downloads 中的版本化檔案作為使用者安裝與分享用交付物。
- 當使用者要求安裝、更新或測試 APK 時，一律執行 `~/install-apk.sh <apk路徑>`。腳本會自動讀取全域 `~/.adb_port` 並進行前置探測，ADB 在線時靜默安裝，離線時自動降級為系統安裝視窗，避免連線失敗中斷。
