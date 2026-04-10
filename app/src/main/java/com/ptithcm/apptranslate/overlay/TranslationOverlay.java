package com.ptithcm.apptranslate.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Paint;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.ptithcm.apptranslate.R;
import com.ptithcm.apptranslate.models.TranslatedLine;

import java.util.List;

/**
 * Overlay panel đơn giản để hiển thị kết quả OCR + Dịch.
 * MVP: 1 panel, kéo được, có nút đóng.
 */
public class TranslationOverlay {

    private final Context appContext;
    private final WindowManager windowManager;

    private View overlayView;
    private WindowManager.LayoutParams params;

    public TranslationOverlay(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Hiển thị bản dịch đè trực tiếp lên vị trí chữ gốc (theo boundingBox OCR).
     */
    public void showInPlace(@NonNull List<TranslatedLine> lines) {
        showInPlace(lines, 0, 0);
    }

    /**
     * @param sourceImageWidth  width (px) của ảnh chụp màn hình dùng cho OCR
     * @param sourceImageHeight height (px) của ảnh chụp màn hình dùng cho OCR
     */
    public void showInPlace(@NonNull List<TranslatedLine> lines, int sourceImageWidth, int sourceImageHeight) {
        dismiss();

        FrameLayout root = new FrameLayout(appContext);
        root.setBackgroundColor(Color.TRANSPARENT);

        InPlaceTranslationView translationView = new InPlaceTranslationView(appContext, lines, sourceImageWidth, sourceImageHeight);
        root.addView(translationView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ImageButton btnClose = new ImageButton(appContext);
        btnClose.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        btnClose.setBackgroundColor(Color.TRANSPARENT);
        btnClose.setContentDescription("Close");
        btnClose.setOnClickListener(v -> dismiss());

        int size = dp(40);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(size, size);
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.topMargin = dp(12);
        closeParams.rightMargin = dp(12);
        root.addView(btnClose, closeParams);

        overlayView = root;

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;

        windowManager.addView(overlayView, params);
    }

    public void show(@NonNull String sourceText, @NonNull String translatedText) {
        dismiss();

        overlayView = LayoutInflater.from(appContext).inflate(R.layout.layout_translation_overlay, null);

        TextView tvSource = overlayView.findViewById(R.id.tvSource);
        TextView tvTranslated = overlayView.findViewById(R.id.tvTranslated);
        ImageButton btnClose = overlayView.findViewById(R.id.btnClose);

        // Only show translated text for readability (hide original text).
        tvSource.setText(sourceText);
        tvSource.setVisibility(View.GONE);
        tvTranslated.setText(translatedText);

        btnClose.setOnClickListener(v -> dismiss());

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.BOTTOM;
        params.x = 0;
        params.y = 0;

        // Cho panel kéo được (nhẹ, đủ MVP)
        overlayView.setOnTouchListener(new DragTouchListener(windowManager, params));

        windowManager.addView(overlayView, params);
    }

    public void dismiss() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                appContext.getResources().getDisplayMetrics()
        );
    }

    private static final class InPlaceTranslationView extends View {
        private final List<TranslatedLine> lines;
        private final int sourceImageWidth;
        private final int sourceImageHeight;
        private final int realScreenWidth;
        private final int realScreenHeight;
        private final Paint bgPaint;
        private final Paint textPaint;

        InPlaceTranslationView(@NonNull Context context, @NonNull List<TranslatedLine> lines, int sourceImageWidth, int sourceImageHeight) {
            super(context);
            this.lines = lines;
            this.sourceImageWidth = sourceImageWidth;
            this.sourceImageHeight = sourceImageHeight;

            int rw = 0;
            int rh = 0;
            try {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    DisplayMetrics m = new DisplayMetrics();
                    // Real metrics include system bars; matches MediaProjection in most cases.
                    wm.getDefaultDisplay().getRealMetrics(m);
                    rw = m.widthPixels;
                    rh = m.heightPixels;
                }
            } catch (Exception ignored) {
            }
            this.realScreenWidth = rw;
            this.realScreenHeight = rh;

            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(Color.WHITE);

            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(Color.BLACK);
            textPaint.setSubpixelText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (lines == null || lines.isEmpty()) return;

            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // Determine whether the capture size matches the *real* screen size or this view.
            // Then map capture -> chosen basis, and compensate for the view's on-screen offset.
            int basisW = w;
            int basisH = h;
            boolean useReal = false;
            if (sourceImageWidth > 0 && sourceImageHeight > 0 && realScreenWidth > 0 && realScreenHeight > 0) {
                long errView = Math.abs((long) w - sourceImageWidth) + Math.abs((long) h - sourceImageHeight);
                long errReal = Math.abs((long) realScreenWidth - sourceImageWidth) + Math.abs((long) realScreenHeight - sourceImageHeight);
                // Use real screen metrics only when it better matches the capture.
                useReal = errReal < errView;
                if (useReal) {
                    basisW = realScreenWidth;
                    basisH = realScreenHeight;
                }
            }

            int offsetX = 0;
            int offsetY = 0;
            if (useReal) {
                int[] loc = new int[2];
                try {
                    getLocationOnScreen(loc);
                    offsetX = loc[0];
                    offsetY = loc[1];
                } catch (Exception ignored) {
                }
            }

            float scaleX = 1f;
            float scaleY = 1f;
            if (sourceImageWidth > 0 && sourceImageHeight > 0) {
                scaleX = basisW / (float) sourceImageWidth;
                scaleY = basisH / (float) sourceImageHeight;
            }

            for (TranslatedLine line : lines) {
                if (line == null) continue;
                Rect box = line.boundingBox;
                String text = line.translatedText;
                if (box == null) continue;
                if (text == null) text = "";
                text = text.trim();
                if (text.isEmpty()) continue;
                if (box.width() <= 0 || box.height() <= 0) continue;

                int left = Math.round(box.left * scaleX) - offsetX;
                int top = Math.round(box.top * scaleY) - offsetY;
                int right = Math.round(box.right * scaleX) - offsetX;
                int bottom = Math.round(box.bottom * scaleY) - offsetY;

                left = Math.max(0, left);
                top = Math.max(0, top);
                right = Math.min(w, right);
                bottom = Math.min(h, bottom);
                if (right <= left || bottom <= top) continue;

                // Expand a tiny bit to better cover glyph edges.
                int pad = 2;
                Rect clamped = new Rect(
                    Math.max(0, left - pad),
                    Math.max(0, top - pad),
                    Math.min(w, right + pad),
                    Math.min(h, bottom + pad)
                );

                // Cover original text area.
                canvas.drawRect(clamped, bgPaint);

                float availableWidth = clamped.width();
                float targetHeightPx = Math.max(10f, clamped.height());

                // 1) Pick a text size whose font metrics height matches the bounding box height.
                // Use a stable base to avoid precision issues.
                float baseSize = 100f;
                textPaint.setTextSize(baseSize);
                Paint.FontMetrics baseFm = textPaint.getFontMetrics();
                float baseHeight = baseFm.descent - baseFm.ascent;
                if (baseHeight <= 0f) baseHeight = 1f;
                float sizeForHeight = baseSize * (targetHeightPx / baseHeight);
                textPaint.setTextSize(Math.max(10f, sizeForHeight));

                // 2) If translation is wider than the original line, shrink to fit width.
                float measured = textPaint.measureText(text);
                if (measured > availableWidth && measured > 0f) {
                    float fitScale = availableWidth / measured;
                    textPaint.setTextSize(Math.max(10f, textPaint.getTextSize() * fitScale));
                }

                // If still too wide, naive ellipsize.
                if (textPaint.measureText(text) > availableWidth) {
                    String ellipsis = "…";
                    int end = text.length();
                    while (end > 0 && textPaint.measureText(text.substring(0, end) + ellipsis) > availableWidth) {
                        end--;
                    }
                    if (end > 0) {
                        text = text.substring(0, end) + ellipsis;
                    }
                }

                // Align baseline near the bottom of the original bounding box (more similar to printed text).
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float x = clamped.left;
                float y = clamped.bottom - fm.descent;
                canvas.drawText(text, x, y, textPaint);
            }
        }
    }

    /** Touch listener kéo overlay */
    private static final class DragTouchListener implements View.OnTouchListener {
        private final WindowManager windowManager;
        private final WindowManager.LayoutParams params;

        private int initialX;
        private int initialY;
        private float initialTouchX;
        private float initialTouchY;

        DragTouchListener(WindowManager windowManager, WindowManager.LayoutParams params) {
            this.windowManager = windowManager;
            this.params = params;
        }

        @Override
        public boolean onTouch(View v, android.view.MotionEvent event) {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    params.x = initialX + (int) (event.getRawX() - initialTouchX);
                    params.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(v, params);
                    return true;
            }
            return false;
        }
    }
}

