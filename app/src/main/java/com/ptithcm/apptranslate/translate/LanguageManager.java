package com.ptithcm.apptranslate.translate;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * MVP config: ưu tiên các cặp ngôn ngữ: EN↔VI, JA→VI, KO→VI.
 * Có thể nâng cấp sau bằng UI chọn ngôn ngữ + lưu SharedPreferences.
 */
public final class LanguageManager {

    private LanguageManager() {}

    private static final String PREFS_NAME = "language_manager";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_TARGET = "target";

    private static volatile boolean initialized = false;
    private static volatile SupportedLang cachedSource = SupportedLang.AUTO;
    private static volatile SupportedLang cachedTarget = SupportedLang.VI;

    /**
     * Load cached values from SharedPreferences (best-effort).
     * Safe to call multiple times.
     */
    public static synchronized void init(@NonNull Context context) {
        if (initialized) return;
        loadFromPrefs(context.getApplicationContext());
        initialized = true;
    }

    /**
     * MVP: mặc định dịch sang tiếng Việt.
     */
    @NonNull
    public static SupportedLang getTargetLang() {
        return cachedTarget;
    }

    /**
     * MVP: để AUTO và dùng Language ID để suy đoán.
     */
    @NonNull
    public static SupportedLang getSourceLang() {
        return cachedSource;
    }

    public static void setSourceLang(@NonNull Context context, @NonNull SupportedLang source) {
        if (source == null) source = SupportedLang.AUTO;
        cachedSource = source;
        persist(context.getApplicationContext());
    }

    public static void setTargetLang(@NonNull Context context, @NonNull SupportedLang target) {
        if (target == null || target == SupportedLang.AUTO) {
            target = SupportedLang.VI;
        }
        cachedTarget = target;
        persist(context.getApplicationContext());
    }

    private static void persist(@NonNull Context appContext) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_SOURCE, cachedSource.name())
                .putString(KEY_TARGET, cachedTarget.name())
                .apply();
        initialized = true;
    }

    private static void loadFromPrefs(@NonNull Context appContext) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        cachedSource = parseLang(prefs.getString(KEY_SOURCE, null), SupportedLang.AUTO);
        cachedTarget = parseLang(prefs.getString(KEY_TARGET, null), SupportedLang.VI);
        if (cachedTarget == SupportedLang.AUTO) {
            cachedTarget = SupportedLang.VI;
        }
    }

    @NonNull
    private static SupportedLang parseLang(String raw, @NonNull SupportedLang fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            return SupportedLang.valueOf(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}

