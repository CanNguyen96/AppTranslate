package com.ptithcm.apptranslate.translate;

import androidx.annotation.NonNull;

/**
 * MVP config: ưu tiên các cặp ngôn ngữ: EN↔VI, JA→VI, KO→VI.
 * Có thể nâng cấp sau bằng UI chọn ngôn ngữ + lưu SharedPreferences.
 */
public final class LanguageManager {

    private LanguageManager() {}

    /**
     * MVP: mặc định dịch sang tiếng Việt.
     */
    @NonNull
    public static SupportedLang getTargetLang() {
        return SupportedLang.VI;
    }

    /**
     * MVP: để AUTO và dùng Language ID để suy đoán.
     */
    @NonNull
    public static SupportedLang getSourceLang() {
        return SupportedLang.AUTO;
    }
}

