package com.ptithcm.apptranslate.translate;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dịch on-device bằng ML Kit.
 * - Nếu source=AUTO: dùng Language ID để đoán (ưu tiên EN/JA/KO/VI)
 * - Cache Translator + cache kết quả dịch để giảm latency giữa các lần quét.
 */
public class OnDeviceTranslator {

    private static final DownloadConditions DEFAULT_DOWNLOAD_CONDITIONS =
            new DownloadConditions.Builder().build();

    private static final Object CACHE_LOCK = new Object();

    private static final int MAX_CACHED_TRANSLATORS = 3;
    private static final LinkedHashMap<String, CachedTranslator> TRANSLATOR_CACHE =
            new LinkedHashMap<>(MAX_CACHED_TRANSLATORS, 0.75f, true);

    private static final int MAX_CACHED_TRANSLATIONS = 512;
    private static final LinkedHashMap<String, String> TRANSLATION_RESULT_CACHE =
            new LinkedHashMap<>(MAX_CACHED_TRANSLATIONS, 0.75f, true);

    private static final class CachedTranslator {
        final Translator translator;
        Task<Void> modelDownloadTask;

        CachedTranslator(@NonNull Translator translator) {
            this.translator = translator;
        }
    }

    private final LanguageIdentifier languageIdentifier;

    public OnDeviceTranslator(@NonNull Context context) {
        this.languageIdentifier = LanguageIdentification.getClient();
    }

    public interface TranslationBatchCallback {
        void onSuccess(@NonNull List<String> translatedLines);

        void onFailure(@NonNull Exception e);
    }

    /**
     * Best-effort pre-download models for the current language selection.
     * Call this when user changes languages or when the service starts.
     */
    public void preloadModels(@NonNull SupportedLang source, @NonNull SupportedLang target) {
        if (target == SupportedLang.AUTO) return;

        if (source == SupportedLang.AUTO) {
            // Practical default: prewarm English -> target (common case) when source is AUTO.
            preloadMlKitPair(TranslateLanguage.ENGLISH, target.mlKitCode);
            return;
        }

        preloadMlKitPair(source.mlKitCode, target.mlKitCode);
    }

    /** Close and clear all cached Translators (rarely needed; mainly for tests/debug). */
    public static void closeAllCachedTranslators() {
        synchronized (CACHE_LOCK) {
            for (Map.Entry<String, CachedTranslator> e : TRANSLATOR_CACHE.entrySet()) {
                try {
                    e.getValue().translator.close();
                } catch (Exception ignored) {
                }
            }
            TRANSLATOR_CACHE.clear();
            TRANSLATION_RESULT_CACHE.clear();
        }
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
     * Dịch theo từng dòng, giữ nguyên thứ tự.
     * - Detect language 1 lần nếu source=AUTO
     * - Cache kết quả dịch theo từng dòng
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
            trimmed.add(t.isEmpty() ? "" : t);
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
                            if (normalized == null) normalized = TranslateLanguage.ENGLISH;
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
                        String normalized = normalizeLangCode(langCode);
                        if (normalized == null) normalized = TranslateLanguage.ENGLISH;
                        translateInternal(text, normalized, target.mlKitCode, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
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
        if (sourceMlKit.equals(targetMlKit)) {
            callback.onSuccess(text);
            return;
        }

        final String langPairKey = cacheKey(sourceMlKit, targetMlKit);

        String cached = getCachedTranslation(langPairKey, text);
        if (cached != null) {
            callback.onSuccess(cached);
            return;
        }

        final CachedTranslator cachedTranslator = getOrCreateCachedTranslator(sourceMlKit, targetMlKit);
        ensureModelDownloaded(langPairKey, cachedTranslator)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        cachedTranslator.translator.translate(text)
                                .addOnSuccessListener(new OnSuccessListener<String>() {
                                    @Override
                                    public void onSuccess(String translatedText) {
                                        putCachedTranslation(langPairKey, text, translatedText);
                                        callback.onSuccess(translatedText);
                                    }
                                })
                                .addOnFailureListener(new OnFailureListener() {
                                    @Override
                                    public void onFailure(@NonNull Exception e) {
                                        invalidateCachedTranslator(langPairKey);
                                        callback.onFailure(e);
                                    }
                                });
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        callback.onFailure(e);
                    }
                });
    }

    private void translateLinesInternal(
            @NonNull List<String> lines,
            @NonNull String sourceMlKit,
            @NonNull String targetMlKit,
            @NonNull TranslationBatchCallback callback
    ) {
        if (sourceMlKit.equals(targetMlKit)) {
            callback.onSuccess(lines);
            return;
        }

        final String langPairKey = cacheKey(sourceMlKit, targetMlKit);
        final CachedTranslator cachedTranslator = getOrCreateCachedTranslator(sourceMlKit, targetMlKit);
        ensureModelDownloaded(langPairKey, cachedTranslator)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void unused) {
                        List<String> out = new ArrayList<>(lines.size());
                        translateNextLine(langPairKey, cachedTranslator.translator, lines, 0, out, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        callback.onFailure(e);
                    }
                });
    }

    private void translateNextLine(
            @NonNull String langPairKey,
            @NonNull Translator translator,
            @NonNull List<String> lines,
            int index,
            @NonNull List<String> out,
            @NonNull TranslationBatchCallback callback
    ) {
        if (index >= lines.size()) {
            callback.onSuccess(out);
            return;
        }

        String current = lines.get(index);
        if (current == null || current.isEmpty()) {
            out.add("");
            translateNextLine(langPairKey, translator, lines, index + 1, out, callback);
            return;
        }

        String cached = getCachedTranslation(langPairKey, current);
        if (cached != null) {
            out.add(cached);
            translateNextLine(langPairKey, translator, lines, index + 1, out, callback);
            return;
        }

        translator.translate(current)
                .addOnSuccessListener(new OnSuccessListener<String>() {
                    @Override
                    public void onSuccess(String translatedText) {
                        putCachedTranslation(langPairKey, current, translatedText);
                        out.add(translatedText);
                        translateNextLine(langPairKey, translator, lines, index + 1, out, callback);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        invalidateCachedTranslator(langPairKey);
                        callback.onFailure(e);
                    }
                });
    }

    private void preloadMlKitPair(@NonNull String sourceMlKit, @NonNull String targetMlKit) {
        if (sourceMlKit.equals(targetMlKit)) return;
        final String key = cacheKey(sourceMlKit, targetMlKit);
        final CachedTranslator cachedTranslator = getOrCreateCachedTranslator(sourceMlKit, targetMlKit);
        ensureModelDownloaded(key, cachedTranslator);
    }

    private static String cacheKey(@NonNull String sourceMlKit, @NonNull String targetMlKit) {
        return sourceMlKit + "->" + targetMlKit;
    }

    private static String translationCacheKey(@NonNull String langPairKey, @NonNull String text) {
        return langPairKey + "\u0000" + text;
    }

    private static String getCachedTranslation(@NonNull String langPairKey, @NonNull String text) {
        synchronized (CACHE_LOCK) {
            return TRANSLATION_RESULT_CACHE.get(translationCacheKey(langPairKey, text));
        }
    }

    private static void putCachedTranslation(
            @NonNull String langPairKey,
            @NonNull String text,
            @NonNull String translated
    ) {
        synchronized (CACHE_LOCK) {
            TRANSLATION_RESULT_CACHE.put(translationCacheKey(langPairKey, text), translated);
            if (TRANSLATION_RESULT_CACHE.size() > MAX_CACHED_TRANSLATIONS) {
                Iterator<Map.Entry<String, String>> it = TRANSLATION_RESULT_CACHE.entrySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
    }

    private static CachedTranslator getOrCreateCachedTranslator(
            @NonNull String sourceMlKit,
            @NonNull String targetMlKit
    ) {
        final String key = cacheKey(sourceMlKit, targetMlKit);
        synchronized (CACHE_LOCK) {
            CachedTranslator cached = TRANSLATOR_CACHE.get(key);
            if (cached != null) return cached;

            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(sourceMlKit)
                    .setTargetLanguage(targetMlKit)
                    .build();

            cached = new CachedTranslator(Translation.getClient(options));
            TRANSLATOR_CACHE.put(key, cached);

            // Evict LRU if needed.
            if (TRANSLATOR_CACHE.size() > MAX_CACHED_TRANSLATORS) {
                Iterator<Map.Entry<String, CachedTranslator>> it = TRANSLATOR_CACHE.entrySet().iterator();
                if (it.hasNext()) {
                    Map.Entry<String, CachedTranslator> eldest = it.next();
                    it.remove();
                    try {
                        eldest.getValue().translator.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            return cached;
        }
    }

    private static Task<Void> ensureModelDownloaded(@NonNull String key, @NonNull CachedTranslator cached) {
        synchronized (CACHE_LOCK) {
            if (cached.modelDownloadTask != null) {
                if (cached.modelDownloadTask.isComplete() && !cached.modelDownloadTask.isSuccessful()) {
                    cached.modelDownloadTask = null;
                }
            }

            if (cached.modelDownloadTask == null) {
                cached.modelDownloadTask = cached.translator.downloadModelIfNeeded(DEFAULT_DOWNLOAD_CONDITIONS);
            }
            return cached.modelDownloadTask;
        }
    }

    private static void invalidateCachedTranslator(@NonNull String key) {
        synchronized (CACHE_LOCK) {
            CachedTranslator cached = TRANSLATOR_CACHE.remove(key);
            if (cached != null) {
                try {
                    cached.translator.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String normalizeLangCode(String langCode) {
        if (langCode == null) return null;
        if ("und".equals(langCode)) return null;

        // Chỉ lấy primary language subtag: en-US -> en
        String primary = langCode;
        int idx = langCode.indexOf('-');
        if (idx > 0) primary = langCode.substring(0, idx);

        primary = primary.toLowerCase(Locale.US);

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

