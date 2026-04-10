package com.ptithcm.apptranslate.services;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.ptithcm.apptranslate.activities.CaptureHelperActivity;
import com.ptithcm.apptranslate.ocr.TextRecognizer;
import com.ptithcm.apptranslate.overlay.TranslationOverlay;
import com.ptithcm.apptranslate.translate.LanguageManager;
import com.ptithcm.apptranslate.translate.OnDeviceTranslator;
import com.ptithcm.apptranslate.translate.SupportedLang;
import com.ptithcm.apptranslate.translate.TranslationCallback;
import com.ptithcm.apptranslate.capture.MediaProjectionTokenStore;
import com.ptithcm.apptranslate.capture.MediaProjectionSession;
import com.ptithcm.apptranslate.models.OcrLine;
import com.ptithcm.apptranslate.models.TranslatedLine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ScreenCaptureService extends Service {

    // Actions
    public static final String ACTION_START_PROJECTION = "com.ptithcm.apptranslate.action.START_PROJECTION";
    public static final String ACTION_CAPTURE_ONCE = "com.ptithcm.apptranslate.action.CAPTURE_ONCE";
    public static final String ACTION_STOP_PROJECTION = "com.ptithcm.apptranslate.action.STOP_PROJECTION";

    private static final String CHANNEL_ID = "screen_capture_channel";
    private static final int NOTIFICATION_ID = 2;

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private int screenWidth, screenHeight, screenDensity;
    private TextRecognizer textRecognizer;
    private OnDeviceTranslator onDeviceTranslator;
    private TranslationOverlay translationOverlay;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection.Callback projectionCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        textRecognizer = new TextRecognizer();
        onDeviceTranslator = new OnDeviceTranslator(this);
        translationOverlay = new TranslationOverlay(this);

        // If the process still has an active MediaProjection session, re-attach.
        if (MediaProjectionSession.isActive()) {
            mediaProjection = MediaProjectionSession.get();
            virtualDisplay = MediaProjectionSession.getVirtualDisplay();
            imageReader = MediaProjectionSession.getImageReader();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. Gọi startForeground NGAY LẬP TỨC để tránh crash
        showNotificationAndStartForeground();

        final String action = intent != null ? intent.getAction() : null;
        Log.d("CAPTURE", "ScreenCaptureService started; action=" + action + "; intent=" + intent + ", extras=" + (intent != null ? intent.getExtras() : null));

        if (ACTION_STOP_PROJECTION.equals(action)) {
            stopProjectionSession();
            stopSelf();
            return START_NOT_STICKY;
        }

        // If we already have an active projection session, we can capture right away.
        if (!ACTION_START_PROJECTION.equals(action) && MediaProjectionSession.isActive()) {
            Log.d("CAPTURE", "Using existing MediaProjection session -> capture");

            // Service can be recreated by the system; ensure capture pipeline is ready.
            mediaProjection = MediaProjectionSession.get();
            if (mediaProjection == null) {
                MediaProjectionSession.clear();
                Toast.makeText(this, "Phiên chụp màn hình đã mất. Hãy cấp quyền lại.", Toast.LENGTH_SHORT).show();
                try {
                    Intent helper = new Intent(this, CaptureHelperActivity.class);
                    helper.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(helper);
                } catch (Exception e) {
                    Log.e("CAPTURE", "Failed to start CaptureHelperActivity", e);
                }
                stopSelf();
                return START_NOT_STICKY;
            }

            try {
                ensureVirtualDisplay();
            } catch (Exception e) {
                Log.e("CAPTURE", "ensureVirtualDisplay failed", e);
                Toast.makeText(this, "Không thể chuẩn bị chụp màn hình: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                stopSelf();
                return START_NOT_STICKY;
            }

            handler.postDelayed(this::captureScreen, 200);
            return START_NOT_STICKY;
        }

        int resultCode = -1;
        Intent resultData = null;

        if (intent != null) {
            resultCode = intent.getIntExtra("RESULT_CODE", -1);
            resultData = intent.getParcelableExtra("RESULT_DATA");
        }

        Log.d("CAPTURE", "ScreenCaptureService received: RESULT_CODE=" + resultCode + ", RESULT_DATA=" + (resultData != null));

        // Fallback: some ROMs/flows may restart the service without extras.
        if ((resultCode == -1 || resultData == null) && MediaProjectionTokenStore.hasToken()) {
            Log.w("CAPTURE", "Missing extras; falling back to MediaProjectionTokenStore (hasToken=true)");
            resultCode = MediaProjectionTokenStore.getResultCode();
            resultData = MediaProjectionTokenStore.getResultData();
            Log.d("CAPTURE", "TokenStore values: RESULT_CODE=" + resultCode + ", RESULT_DATA=" + (resultData != null));
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            try {
                setupMediaProjection(resultCode, resultData);
            } catch (Exception e) {
                Log.e("CAPTURE", "setupMediaProjection failed", e);
                Toast.makeText(this, "Không thể khởi tạo chụp màn hình: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                stopSelf();
                return START_NOT_STICKY;
            }

            Toast.makeText(this, "Đang quét màn hình...", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::captureScreen, 400);
        } else {
            Log.e("CAPTURE", "No valid MediaProjection token. resultCode=" + resultCode + ", data=" + (resultData != null));
            Toast.makeText(this, "Thiếu quyền chụp màn hình. Hãy cấp quyền.", Toast.LENGTH_SHORT).show();

            // Prompt user to grant again
            try {
                Intent helper = new Intent(this, CaptureHelperActivity.class);
                helper.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(helper);
            } catch (Exception e) {
                Log.e("CAPTURE", "Failed to start CaptureHelperActivity from service", e);
            }

            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void showNotificationAndStartForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Screen Capture", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Đang chuẩn bị dịch")
                .setContentText("Đang quét màn hình...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        // Bắt đầu Foreground Service với đúng type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void setupMediaProjection(int resultCode, Intent resultData) {
        MediaProjectionManager mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpManager == null) {
            throw new IllegalStateException("MediaProjectionManager is null");
        }

        // If a previous session exists, tear it down first.
        stopProjectionSession();

        mediaProjection = mpManager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            throw new IllegalStateException("MediaProjection is null");
        }

        MediaProjectionSession.set(mediaProjection);

        // When starting a NEW session, clear any previous cached pipeline.
        MediaProjectionSession.setVirtualDisplay(null);
        MediaProjectionSession.setImageReader(null);
        virtualDisplay = null;
        imageReader = null;

        // IMPORTANT (Android 14+/some ROMs): must register a callback before starting capture.
        if (projectionCallback == null) {
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.w("CAPTURE", "MediaProjection stopped by system/user");
                    handler.post(() -> {
                        stopProjectionSession();
                        stopSelf();
                    });
                }
            };
        }

        mediaProjection.registerCallback(projectionCallback, handler);

        // Create capture pipeline
        ensureVirtualDisplay();

        Log.d("CAPTURE", "VirtualDisplay created: " + (virtualDisplay != null));
    }

    private void ensureVirtualDisplay() {
        if (mediaProjection == null) {
            throw new IllegalStateException("MediaProjection is null");
        }

        // Restore cached pipeline if service was recreated.
        if (virtualDisplay == null) {
            virtualDisplay = MediaProjectionSession.getVirtualDisplay();
        }
        if (imageReader == null) {
            imageReader = MediaProjectionSession.getImageReader();
        }

        // Always (re)compute screen metrics. The Service is often recreated between taps, and
        // screenWidth/screenHeight would otherwise remain 0 when we reuse the cached pipeline.
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            throw new IllegalStateException("WindowManager is null");
        }

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
        }

        // If we already have everything, reuse it.
        if (virtualDisplay != null && imageReader != null) return;

        if (screenWidth <= 0 || screenHeight <= 0 || screenDensity <= 0) {
            throw new IllegalStateException("Invalid screen metrics w=" + screenWidth + " h=" + screenHeight + " dpi=" + screenDensity);
        }

        Log.d("CAPTURE", "Screen metrics w=" + screenWidth + " h=" + screenHeight + " dpi=" + screenDensity);

        // IMPORTANT: some devices/ROMs (as shown in your log) do NOT allow creating
        // multiple VirtualDisplays on the same MediaProjection instance.
        // So we create it ONCE per session and keep it until the session ends.
        if (imageReader == null) {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
            MediaProjectionSession.setImageReader(imageReader);
        }

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );
            MediaProjectionSession.setVirtualDisplay(virtualDisplay);
        }
    }

    private void stopProjectionSession() {
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignored) {
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }

        if (mediaProjection != null) {
            try {
                if (projectionCallback != null) {
                    mediaProjection.unregisterCallback(projectionCallback);
                }
            } catch (Exception ignored) {
            }
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }

        // Also clear virtual display pipeline so it can be recreated next time.
        // (stopProjectionSession already releases/close, keep as-is)

        MediaProjectionSession.clear();
    }

    private void captureScreen() {
        if (imageReader == null) {
            Log.e("CAPTURE", "imageReader is null");
            stopSelf();
            return;
        }

        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                Log.w("CAPTURE", "Image is null, retrying...");
                handler.postDelayed(this::captureScreen, 300);
                return;
            }

            Bitmap bitmap = null;
            Bitmap croppedBitmap = null;

            try {
                final int imageWidth = image.getWidth();
                final int imageHeight = image.getHeight();
                if (imageWidth <= 0 || imageHeight <= 0) {
                    Log.w("CAPTURE", "Invalid image size w=" + imageWidth + " h=" + imageHeight + ", retrying...");
                    handler.postDelayed(this::captureScreen, 200);
                    return;
                }

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * imageWidth;

                // Keep fields in sync for any downstream uses/logging.
                screenWidth = imageWidth;
                screenHeight = imageHeight;

                buffer.rewind();

                bitmap = Bitmap.createBitmap(
                        imageWidth + Math.max(0, rowPadding) / pixelStride,
                        imageHeight,
                        Bitmap.Config.ARGB_8888
                );
                bitmap.copyPixelsFromBuffer(buffer);
                croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, imageWidth, imageHeight);
            } catch (Exception e) {
                Log.e("CAPTURE", "Failed to convert image to bitmap", e);
                Toast.makeText(this, "Không thể xử lý ảnh màn hình", Toast.LENGTH_SHORT).show();
                stopSelf();
                return;
            } finally {
                image.close();
                if (bitmap != null && bitmap != croppedBitmap) {
                    bitmap.recycle();
                }
            }

            final int sourceImageWidth = screenWidth;
            final int sourceImageHeight = screenHeight;
            final Bitmap finalBitmapForOcr = croppedBitmap;
            textRecognizer.recognizeLines(finalBitmapForOcr, new TextRecognizer.OnLinesRecognizedListener() {
                @Override
                public void onSuccess(List<OcrLine> lines) {
                    // OCR xong thì recycle bitmap (không cần bitmap nữa, chỉ cần text + boundingBox)
                    if (finalBitmapForOcr != null && !finalBitmapForOcr.isRecycled()) {
                        finalBitmapForOcr.recycle();
                    }

                    if (lines == null || lines.isEmpty()) {
                        Toast.makeText(ScreenCaptureService.this, "Không tìm thấy văn bản nào", Toast.LENGTH_SHORT).show();
                        stopSelf();
                        return;
                    }

                    // Lọc line hợp lệ (có bounding box và kích thước > 0)
                    List<OcrLine> filteredLines = new ArrayList<>();
                    List<String> lineTexts = new ArrayList<>();
                    for (OcrLine l : lines) {
                        if (l == null) continue;
                        if (l.text == null) continue;
                        String t = l.text.trim();
                        if (t.isEmpty()) continue;
                        if (l.boundingBox == null) continue;
                        if (l.boundingBox.width() <= 0 || l.boundingBox.height() <= 0) continue;
                        filteredLines.add(l);
                        lineTexts.add(t);
                    }

                    if (filteredLines.isEmpty()) {
                        Toast.makeText(ScreenCaptureService.this, "Không tìm thấy văn bản nào", Toast.LENGTH_SHORT).show();
                        stopSelf();
                        return;
                    }

                    SupportedLang source = LanguageManager.getSourceLang();
                    SupportedLang target = LanguageManager.getTargetLang();

                    onDeviceTranslator.translateLines(lineTexts, source, target, new OnDeviceTranslator.TranslationBatchCallback() {
                        @Override
                        public void onSuccess(List<String> translatedLines) {
                            if (translatedLines == null || translatedLines.isEmpty()) {
                                Toast.makeText(ScreenCaptureService.this, "Không có kết quả dịch", Toast.LENGTH_SHORT).show();
                                stopSelf();
                                return;
                            }

                            // Ghép boundingBox với text đã dịch theo đúng thứ tự
                            int count = Math.min(filteredLines.size(), translatedLines.size());
                            List<TranslatedLine> overlayLines = new ArrayList<>(count);
                            for (int i = 0; i < count; i++) {
                                String translated = translatedLines.get(i);
                                if (translated == null) translated = "";
                                overlayLines.add(new TranslatedLine(translated, filteredLines.get(i).boundingBox));
                            }

                            try {
                                translationOverlay.showInPlace(overlayLines, sourceImageWidth, sourceImageHeight);
                            } catch (Exception e) {
                                // Nếu overlay lỗi (chưa cấp permission), fallback bằng Toast
                                String preview = overlayLines.get(0).translatedText;
                                Toast.makeText(ScreenCaptureService.this,
                                        preview.substring(0, Math.min(preview.length(), 150)),
                                        Toast.LENGTH_LONG).show();
                            }
                            stopSelf();
                        }

                        @Override
                        public void onFailure(Exception e) {
                            Log.e("CAPTURE", "Translation failed", e);
                            Toast.makeText(ScreenCaptureService.this, "Lỗi dịch: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            stopSelf();
                        }
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    if (finalBitmapForOcr != null && !finalBitmapForOcr.isRecycled()) {
                        finalBitmapForOcr.recycle();
                    }
                    Log.e("CAPTURE", "OCR failed", e);
                    Toast.makeText(ScreenCaptureService.this, "Lỗi OCR: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    stopSelf();
                }
            });
        } catch (Exception e) {
            Log.e("CAPTURE", "captureScreen failed", e);
            Toast.makeText(this, "Chụp màn hình thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            stopSelf();
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        // Do NOT stop projection session here, otherwise user will be prompted again next time.
        // Also: do NOT release VirtualDisplay/ImageReader here, otherwise next tap would need
        // to create a new VirtualDisplay, which some ROMs forbids and throws SecurityException.
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
