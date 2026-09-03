# Crew Helper 專案規範

## APK 交付

- 每次成功編譯並完成 APK 簽名驗證後，預設將最終 APK 複製到：
  `/data/data/com.termux/files/home/storage/downloads/CrewHelper-v<versionName>.apk`
- Downloads 交付檔名必須包含版本，例如：`CrewHelper-v1.8.23.apk`。
- 專案內的 `CrewHelper.apk` 保留為建置來源；Downloads 中的版本化檔案作為使用者安裝與分享用交付物。
