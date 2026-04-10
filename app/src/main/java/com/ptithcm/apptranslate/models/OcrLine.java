package com.ptithcm.apptranslate.models;

import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class OcrLine {
    @NonNull
    public final String text;

    @Nullable
    public final Rect boundingBox;

    public OcrLine(@NonNull String text, @Nullable Rect boundingBox) {
        this.text = text;
        this.boundingBox = boundingBox;
    }
}
