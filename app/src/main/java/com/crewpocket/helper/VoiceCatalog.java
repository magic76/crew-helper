package com.crewpocket.helper;

/** 0106: immutable Gemini Live voice metadata extracted from MainActivity. */
final class VoiceInfo {
    public final String name;
    public final boolean isFemale;
    public final String zhDesc;
    public final String enDesc;
    public final float pitch;

    public VoiceInfo(String name, boolean isFemale, String zhDesc, String enDesc, float pitch) {
        this.name = name;
        this.isFemale = isFemale;
        this.zhDesc = zhDesc;
        this.enDesc = enDesc;
        this.pitch = pitch;
    }
}

final class VoiceCatalog {
    private VoiceCatalog() {}

    static final VoiceInfo[] ALL_VOICES = new VoiceInfo[]{
        // Female (15)
        new VoiceInfo("Kore", true, "自然放鬆 · 溫柔沉穩", "Relaxed & Natural · Gentle", 1.15f),
        new VoiceInfo("Aoede", true, "清澈優雅 · 溫柔細膩", "Breathy & Gentle · Fairy Tale", 1.18f),
        new VoiceInfo("Leda", true, "年輕活潑 · 朝氣蓬勃", "Youthful & Bright", 1.25f),
        new VoiceInfo("Callirrhoe", true, "輕快悠閒 · 甜美清晰", "Easygoing & Sweet", 1.20f),
        new VoiceInfo("Autonoe", true, "明亮靈動 · 陽光開朗", "Bright & Lively", 1.22f),
        new VoiceInfo("Despina", true, "柔順舒適 · 抑揚頓挫", "Smooth & Fluent", 1.12f),
        new VoiceInfo("Erinome", true, "清新純淨 · 清楚動聽", "Clear & Melodic", 1.16f),
        new VoiceInfo("Laomedeia", true, "活潑俏皮 · 靈巧生動", "Cheerful & Playful", 1.26f),
        new VoiceInfo("Achernar", true, "柔和舒緩 · 靜謐溫暖", "Soft & Soothing", 1.05f),
        new VoiceInfo("Vindemiatrix", true, "溫柔親切 · 慈祥包容", "Gentle & Kind", 1.08f),
        new VoiceInfo("Sadachbia", true, "生動鮮明 · 富有情感", "Vivid & Expressive", 1.14f),
        new VoiceInfo("Sulafat", true, "溫暖安撫 · 睡前繪本", "Warm & Bedtime", 1.02f),
        new VoiceInfo("Algieba", true, "圓潤甜美 · 娓娓道來", "Rounded & Sweet", 1.10f),
        new VoiceInfo("Pulcherrima", true, "優雅前進 · 堅定自信", "Luminous & Elegant", 1.13f),
        new VoiceInfo("Achird", true, "友善鄰家 · 隨和親切", "Friendly & Approachable", 1.18f),

        // Male (15)
        new VoiceInfo("Puck", false, "童趣歡快 · 預設推薦", "Playful & Cheerful · Recommended", 0.95f),
        new VoiceInfo("Charon", false, "沉穩專業 · 磁性冷靜", "Deep & Confident", 0.80f),
        new VoiceInfo("Fenrir", false, "低沉冒險 · 威嚴有力", "Adventurous & Powerful", 0.75f),
        new VoiceInfo("Orus", false, "沉著清晰 · 條理分明", "Firm & Articulate", 0.88f),
        new VoiceInfo("Zephyr", false, "溫暖明亮 · 撫慰人心", "Warm & Bright", 0.92f),
        new VoiceInfo("Enceladus", false, "氣聲磁性 · 溫暖陪伴", "Breathy & Warm", 0.85f),
        new VoiceInfo("Iapetus", false, "踏實清晰 · 值得信賴", "Grounded & Clear", 0.82f),
        new VoiceInfo("Umbriel", false, "輕鬆休閒 · 幽默自在", "Easygoing & Calm", 0.88f),
        new VoiceInfo("Algenib", false, "沙啞磁性 · 歷練說書", "Husky & Storyteller", 0.78f),
        new VoiceInfo("Rasalgethi", false, "知識博學 · 沉穩說理", "Wise & Articulate", 0.86f),
        new VoiceInfo("Alnilam", false, "堅定沉著 · 宏亮有力", "Resonant & Firm", 0.76f),
        new VoiceInfo("Schedar", false, "平穩安定 · 故事說書", "Steady & Measured", 0.84f),
        new VoiceInfo("Gacrux", false, "成熟醇厚 · 威嚴可靠", "Mature & Rich", 0.72f),
        new VoiceInfo("Zubenelgenubi", false, "隨和親近 · 幽默自然", "Conversational & Warm", 0.90f),
        new VoiceInfo("Sadaltager", false, "博學智慧 · 娓娓道來", "Wise & Engaging", 0.86f)
    };
}
