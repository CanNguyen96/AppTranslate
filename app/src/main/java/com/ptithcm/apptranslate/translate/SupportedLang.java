package com.ptithcm.apptranslate.translate;

import androidx.annotation.NonNull;

import com.google.mlkit.nl.translate.TranslateLanguage;

public enum SupportedLang {
    AUTO("auto", "Tự động"),
    VI(TranslateLanguage.VIETNAMESE, "Tiếng Việt"),
    EN(TranslateLanguage.ENGLISH, "English"),
    JA(TranslateLanguage.JAPANESE, "日本語"),
    KO(TranslateLanguage.KOREAN, "한국어");

    public final String mlKitCode;
    public final String displayName;

    SupportedLang(@NonNull String mlKitCode, @NonNull String displayName) {
        this.mlKitCode = mlKitCode;
        this.displayName = displayName;
    }
}
