package com.ptithcm.apptranslate.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import com.ptithcm.apptranslate.models.OcrLine;

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

    private final com.google.mlkit.vision.text.TextRecognizer recognizer;

    public TextRecognizer() {
        // Khởi tạo bộ nhận diện (mặc định là bảng chữ cái Latinh)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public void recognizeText(Bitmap bitmap, final OnTextRecognizedListener listener) {
        if (bitmap == null) {
            listener.onFailure(new Exception("Bitmap is null"));
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
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
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text visionText) {
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
                        listener.onSuccess(lines);
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
            @NonNull Executor callbackExecutor,
            @NonNull final OnLinesRecognizedListener listener
    ) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        recognizer.process(image)
                .addOnSuccessListener(callbackExecutor, new OnSuccessListener<Text>() {
                    @Override
                    public void onSuccess(Text visionText) {
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
                        listener.onSuccess(lines);
                    }
                })
                .addOnFailureListener(callbackExecutor, new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        listener.onFailure(e);
                    }
                });
    }
}