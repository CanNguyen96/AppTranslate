package com.ptithcm.apptranslate.translate;

public interface TranslationCallback {
    void onSuccess(String translatedText);

    void onFailure(Exception e);
}
