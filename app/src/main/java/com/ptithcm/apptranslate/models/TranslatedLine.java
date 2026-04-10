package com.ptithcm.apptranslate.models;

import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class TranslatedLine {
    @NonNull
    public final String translatedText;

    @Nullable
    public final Rect boundingBox;

    public TranslatedLine(@NonNull String translatedText, @Nullable Rect boundingBox) {
        this.translatedText = translatedText;
        this.boundingBox = boundingBox;
    }
}
