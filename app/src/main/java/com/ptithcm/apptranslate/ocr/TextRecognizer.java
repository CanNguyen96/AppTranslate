package com.ptithcm.apptranslate.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import com.ptithcm.apptranslate.models.OcrLine;
import com.ptithcm.apptranslate.translate.SupportedLang;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class TextRecognizer {

    public interface OnTextRecognizedListener {
        void onSuccess(String text);
        void onFailure(Exception e);
    }

    public interface OnLinesRecognizedListener {
        void onSuccess(@NonNull List<OcrLine> lines);
        void onFailure(@NonNull Exception e);
    }

    private final com.google.mlkit.vision.text.TextRecognizer latinRecognizer;
    private final com.google.mlkit.vision.text.TextRecognizer chineseRecognizer;

    public TextRecognizer() {
        // Mặc định: Latin.
        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        // Thêm recognizer tiếng Trung để OCR ra chữ Trung (Han script).
        chineseRecognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build()
        );
    }

    @NonNull
    private com.google.mlkit.vision.text.TextRecognizer pickRecognizer(@NonNull SupportedLang sourceHint) {
        if (sourceHint == SupportedLang.ZH) {
            return chineseRecognizer;
        }
        return latinRecognizer;
    }

    @NonNull
    private static List<OcrLine> extractLines(@NonNull Text visionText) {
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String txt = line.getText();
                if (txt == null) continue;
                txt = txt.trim();
                if (txt.isEmpty()) continue;

                Rect box = line.getBoundingBox();
                lines.add(new OcrLine(txt, box != null ? new Rect(box) : null));
            }
        }
        return lines;
    }

    public void recognizeText(Bitmap bitmap, final OnTextRecognizedListener listener) {
        if (bitmap == null) {
            listener.onFailure(new Exception("Bitmap is null"));
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        latinRecognizer.process(image)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text visionText) {
                        listener.onSuccess(visionText.getText());
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        listener.onFailure(e);
                    }
                });
    }

    /**
     * Trả về danh sách dòng (line) kèm boundingBox để có thể vẽ đè trực tiếp lên màn hình.
     */
    public void recognizeLines(@NonNull Bitmap bitmap, @NonNull final OnLinesRecognizedListener listener) {
        recognizeLines(bitmap, SupportedLang.AUTO, listener);
    }

    public void recognizeLines(
            @NonNull Bitmap bitmap,
            @NonNull SupportedLang sourceHint,
            @NonNull final OnLinesRecognizedListener listener
    ) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        if (sourceHint == SupportedLang.AUTO) {
            latinRecognizer.process(image)
                    .addOnSuccessListener(new OnSuccessListener<Text>() {
                        @Override
                        public void onSuccess(Text visionText) {
                            List<OcrLine> lines = extractLines(visionText);
                            if (!lines.isEmpty()) {
                                listener.onSuccess(lines);
                                return;
                            }

                            // Fallback: thử OCR tiếng Trung nếu Latin ra rỗng.
                            chineseRecognizer.process(image)
                                    .addOnSuccessListener(new OnSuccessListener<Text>() {
                                        @Override
                                        public void onSuccess(Text visionText2) {
                                            listener.onSuccess(extractLines(visionText2));
                                        }
                                    })
                                    .addOnFailureListener(new OnFailureListener() {
                                        @Override
                                        public void onFailure(@NonNull Exception e) {
                                            listener.onFailure(e);
                                        }
                                    });
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            listener.onFailure(e);
                        }
                    });
            return;
        }

        pickRecognizer(sourceHint).process(image)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text visionText) {
                        listener.onSuccess(extractLines(visionText));
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        listener.onFailure(e);
                    }
                });
    }

    /**
     * Same as recognizeLines() but allows supplying an Executor for callbacks.
     * Useful when you want to keep OCR + post-processing off the main thread.
     */
    public void recognizeLines(
            @NonNull Bitmap bitmap,
            @NonNull SupportedLang sourceHint,
            @NonNull Executor callbackExecutor,
            @NonNull final OnLinesRecognizedListener listener
    ) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        if (sourceHint == SupportedLang.AUTO) {
            latinRecognizer.process(image)
                    .addOnSuccessListener(callbackExecutor, new OnSuccessListener<Text>() {
                        @Override
                        public void onSuccess(Text visionText) {
                            List<OcrLine> lines = extractLines(visionText);
                            if (!lines.isEmpty()) {
                                listener.onSuccess(lines);
                                return;
                            }

                            // Fallback: thử OCR tiếng Trung nếu Latin ra rỗng.
                            chineseRecognizer.process(image)
                                    .addOnSuccessListener(callbackExecutor, new OnSuccessListener<Text>() {
                                        @Override
                                        public void onSuccess(Text visionText2) {
                                            listener.onSuccess(extractLines(visionText2));
                                        }
                                    })
                                    .addOnFailureListener(callbackExecutor, new OnFailureListener() {
                                        @Override
                                        public void onFailure(@NonNull Exception e) {
                                            listener.onFailure(e);
                                        }
                                    });
                        }
                    })
                    .addOnFailureListener(callbackExecutor, new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            listener.onFailure(e);
                        }
                    });
            return;
        }

        pickRecognizer(sourceHint).process(image)
                .addOnSuccessListener(callbackExecutor, new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text visionText) {
                        listener.onSuccess(extractLines(visionText));
                    }
                })
                .addOnFailureListener(callbackExecutor, new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        listener.onFailure(e);
                    }
                });
    }

    /** Backward compatible overload: defaults to AUTO (Latin). */
    public void recognizeLines(
            @NonNull Bitmap bitmap,
            @NonNull Executor callbackExecutor,
            @NonNull final OnLinesRecognizedListener listener
    ) {
        recognizeLines(bitmap, SupportedLang.AUTO, callbackExecutor, listener);
    }
}