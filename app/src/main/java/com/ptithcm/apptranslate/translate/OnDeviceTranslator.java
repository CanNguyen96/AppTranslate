package com.ptithcm.apptranslate.translate;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Dịch on-device bằng ML Kit.
 * - Nếu source=AUTO: dùng Language ID để đoán (ưu tiên EN/JA/KO/VI)
 * - Tự tải model khi cần (downloadModelIfNeeded)
 */
public class OnDeviceTranslator {

    private final Context appContext;
    private final LanguageIdentifier languageIdentifier;

    public OnDeviceTranslator(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.languageIdentifier = LanguageIdentification.getClient();
    }

    public interface TranslationBatchCallback {
        void onSuccess(@NonNull List<String> translatedLines);
        void onFailure(@NonNull Exception e);
    }

    public void translate(
            @NonNull String input,
            @NonNull SupportedLang source,
            @NonNull SupportedLang target,
            @NonNull TranslationCallback callback
    ) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            callback.onFailure(new IllegalArgumentException("Input text is empty"));
            return;
        }

        if (target == SupportedLang.AUTO) {
            callback.onFailure(new IllegalArgumentException("Target language cannot be AUTO"));
            return;
        }

        if (source == SupportedLang.AUTO) {
            detectThenTranslate(trimmed, target, callback);
        } else {
            translateInternal(trimmed, source.mlKitCode, target.mlKitCode, callback);
        }
    }

    /**
     * Dịch theo từng dòng, giữ nguyên thứ tự. Dùng 1 Translator instance để giảm overhead.
     */
    public void translateLines(
            @NonNull List<String> inputs,
            @NonNull SupportedLang source,
            @NonNull SupportedLang target,
            @NonNull TranslationBatchCallback callback
    ) {
        if (inputs.isEmpty()) {
            callback.onSuccess(Collections.<String>emptyList());
            return;
        }

        if (target == SupportedLang.AUTO) {
            callback.onFailure(new IllegalArgumentException("Target language cannot be AUTO"));
            return;
        }

        List<String> trimmed = new ArrayList<>(inputs.size());
        for (String s : inputs) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) {
                // Maintain alignment; caller should ideally filter empties before calling.
                trimmed.add("");
            } else {
                trimmed.add(t);
            }
        }

        if (source == SupportedLang.AUTO) {
            // Detect language once on combined text.
            StringBuilder sb = new StringBuilder();
            for (String s : trimmed) {
                if (!s.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(s);
                }
            }
            final String detectText = sb.toString();
            if (detectText.isEmpty()) {
                callback.onSuccess(trimmed);
                return;
            }

            languageIdentifier.identifyLanguage(detectText)
                    .addOnSuccessListener(new OnSuccessListener<String>() {
                        @Override
                        public void onSuccess(String langCode) {
                            String normalized = normalizeLangCode(langCode);
                            if (normalized == null) {
                                normalized = TranslateLanguage.ENGLISH;
                            }
                            translateLinesInternal(trimmed, normalized, target.mlKitCode, callback);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            translateLinesInternal(trimmed, TranslateLanguage.ENGLISH, target.mlKitCode, callback);
                        }
                    });
        } else {
            translateLinesInternal(trimmed, source.mlKitCode, target.mlKitCode, callback);
        }
    }

    private void detectThenTranslate(
            @NonNull String text,
            @NonNull SupportedLang target,
            @NonNull TranslationCallback callback
    ) {
        languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener(new OnSuccessListener<String>() {
                    @Override
                    public void onSuccess(String langCode) {
                        // ML Kit language-id trả về BCP-47 (ví dụ: "en", "ja", "ko", "vi").
                        // Nếu und => fallback.
                        String normalized = normalizeLangCode(langCode);
                        if (normalized == null) {
                            // fallback: đoán theo locale hoặc en
                            normalized = TranslateLanguage.ENGLISH;
                        }
                        translateInternal(text, normalized, target.mlKitCode, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        // fallback an toàn
                        translateInternal(text, TranslateLanguage.ENGLISH, target.mlKitCode, callback);
                    }
                });
    }

    private void translateInternal(
            @NonNull String text,
            @NonNull String sourceMlKit,
            @NonNull String targetMlKit,
            @NonNull TranslationCallback callback
    ) {
        // Nếu source==target thì trả lại nguyên văn
        if (sourceMlKit.equals(targetMlKit)) {
            callback.onSuccess(text);
            return;
        }

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceMlKit)
                .setTargetLanguage(targetMlKit)
                .build();

        final Translator translator = Translation.getClient(options);
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        translator.translate(text)
                                .addOnSuccessListener(new OnSuccessListener<String>() {
                                    @Override
                                    public void onSuccess(String translatedText) {
                                        callback.onSuccess(translatedText);
                                        translator.close();
                                    }
                                })
                                .addOnFailureListener(new OnFailureListener() {
                                    @Override
                                    public void onFailure(@NonNull Exception e) {
                                        callback.onFailure(e);
                                        translator.close();
                                    }
                                });
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        callback.onFailure(e);
                        translator.close();
                    }
                });
    }

    private void translateLinesInternal(
            @NonNull List<String> lines,
            @NonNull String sourceMlKit,
            @NonNull String targetMlKit,
            @NonNull TranslationBatchCallback callback
    ) {
        // Nếu source==target thì trả lại nguyên văn
        if (sourceMlKit.equals(targetMlKit)) {
            callback.onSuccess(lines);
            return;
        }

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceMlKit)
                .setTargetLanguage(targetMlKit)
                .build();

        final Translator translator = Translation.getClient(options);
        DownloadConditions conditions = new DownloadConditions.Builder().build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        List<String> out = new ArrayList<>(lines.size());
                        translateNextLine(translator, lines, 0, out, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        callback.onFailure(e);
                        translator.close();
                    }
                });
    }

    private void translateNextLine(
            @NonNull Translator translator,
            @NonNull List<String> lines,
            int index,
            @NonNull List<String> out,
            @NonNull TranslationBatchCallback callback
    ) {
        if (index >= lines.size()) {
            callback.onSuccess(out);
            translator.close();
            return;
        }

        String current = lines.get(index);
        if (current == null || current.isEmpty()) {
            out.add("");
            translateNextLine(translator, lines, index + 1, out, callback);
            return;
        }

        translator.translate(current)
                .addOnSuccessListener(new OnSuccessListener<String>() {
                    @Override
                    public void onSuccess(String translatedText) {
                        out.add(translatedText);
                        translateNextLine(translator, lines, index + 1, out, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        callback.onFailure(e);
                        translator.close();
                    }
                });
    }

    private String normalizeLangCode(String langCode) {
        if (langCode == null) return null;
        if ("und".equals(langCode)) return null;

        // Chỉ lấy primary language subtag: en-US -> en
        String primary = langCode;
        int idx = langCode.indexOf('-');
        if (idx > 0) primary = langCode.substring(0, idx);

        primary = primary.toLowerCase(Locale.US);

        // Map qua ML Kit TranslateLanguage nếu hỗ trợ
        String translated = TranslateLanguage.fromLanguageTag(primary);
        if (translated == null) return null;

        // MVP: ưu tiên các ngôn ngữ mình hỗ trợ
        if (translated.equals(TranslateLanguage.ENGLISH)
                || translated.equals(TranslateLanguage.VIETNAMESE)
                || translated.equals(TranslateLanguage.JAPANESE)
                || translated.equals(TranslateLanguage.KOREAN)) {
            return translated;
        }

        return null;
    }
}

