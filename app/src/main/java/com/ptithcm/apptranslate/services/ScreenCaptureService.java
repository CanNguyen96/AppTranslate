package com.ptithcm.apptranslate.services;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private static final int MAX_OCR_WIDTH_PX = 1080;
    private final ExecutorService captureExecutor = Executors.newSingleThreadExecutor();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection.Callback projectionCallback;

    private volatile boolean mediaProjectionFgsTypeDenied = false;
    private int nullImageRetries = 0;
    private static final int MAX_NULL_IMAGE_RETRIES = 6;

    @Override
    public void onCreate() {
        super.onCreate();
        LanguageManager.init(this);
        textRecognizer = new TextRecognizer();
        onDeviceTranslator = new OnDeviceTranslator(this);
        translationOverlay = new TranslationOverlay(this);

        // Pre-download models early to reduce first-tap latency.
        onDeviceTranslator.preloadModels(LanguageManager.getSourceLang(), LanguageManager.getTargetLang());

        // If the process still has an active MediaProjection session, re-attach.
        if (MediaProjectionSession.isActive()) {
            mediaProjection = MediaProjectionSession.get();
            virtualDisplay = MediaProjectionSession.getVirtualDisplay();
            imageReader = MediaProjectionSession.getImageReader();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : null;
        Log.d("CAPTURE", "ScreenCaptureService started; action=" + action + "; intent=" + intent + ", extras=" + (intent != null ? intent.getExtras() : null));

        // STOP must take priority over any capture fast-path.
        if (ACTION_STOP_PROJECTION.equals(action)) {
            // Start FGS ASAP (avoid mediaProjection type when we don't need it)
            showNotificationAndStartForeground(false);
            stopProjectionSession();
            stopSelf();
            return START_NOT_STICKY;
        }

        // If we already have an active projection session, we can capture right away.
        if (!ACTION_START_PROJECTION.equals(action) && MediaProjectionSession.isActive()) {
            Log.d("CAPTURE", "Using existing MediaProjection session -> capture");

            // 1) Start FGS ASAP with mediaProjection type (required to use MediaProjection on Android 14+)
            if (!showNotificationAndStartForeground(true)) {
                stopSelf();
                return START_NOT_STICKY;
            }

            if (mediaProjectionFgsTypeDenied) {
                Log.w("CAPTURE", "mediaProjection FGS type denied; need to grant permission again");
                promptUserToGrantCapturePermission();
                stopSelf();
                return START_NOT_STICKY;
            }

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

        // 1) Start FGS ASAP.
        // For ACTION_START_PROJECTION we must be a mediaProjection-typed FGS BEFORE calling getMediaProjection.
        boolean requiresMediaProjectionType = (ACTION_START_PROJECTION.equals(action)
                && resultCode == Activity.RESULT_OK
                && resultData != null);

        if (!showNotificationAndStartForeground(requiresMediaProjectionType)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (requiresMediaProjectionType && mediaProjectionFgsTypeDenied) {
            Log.w("CAPTURE", "mediaProjection FGS type denied before setup; need to grant permission again");
            promptUserToGrantCapturePermission();
            stopSelf();
            return START_NOT_STICKY;
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

            // Promote to mediaProjection FGS type after MediaProjection is established (Android 14+).
            showNotificationAndStartForeground(true);
            if (mediaProjectionFgsTypeDenied) {
                Log.w("CAPTURE", "mediaProjection FGS type denied even after setup; prompting user to grant again");
                stopProjectionSession();
                promptUserToGrantCapturePermission();
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

    private boolean showNotificationAndStartForeground(boolean useMediaProjectionType) {
        mediaProjectionFgsTypeDenied = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Screen Capture", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        // If notifications are blocked, starting an FGS may be denied/crash on newer Android.
        try {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                Toast.makeText(this, "Hãy bật Thông báo cho ứng dụng để dùng chế độ dịch nền.", Toast.LENGTH_LONG).show();
                return false;
            }
        } catch (Exception ignored) {
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Đang chuẩn bị dịch")
                .setContentText("Đang quét màn hình...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        // Android 14+: Starting an FGS with type mediaProjection requires the app-op/permission granted
        // via MediaProjection. If we don't have it yet, start as specialUse and promote later.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= 34 && !useMediaProjectionType) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }

            try {
                startForeground(NOTIFICATION_ID, notification, type);
                return true;
            } catch (SecurityException se) {
                Log.e("CAPTURE", "startForeground(type=" + type + ") denied; falling back", se);
                if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) {
                    mediaProjectionFgsTypeDenied = true;
                }
                try {
                    // Fallback 1: specialUse on Android 14+
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                        return true;
                    }
                } catch (SecurityException ignored) {
                }

                // Fallback 2: startForeground without explicit type (best-effort)
                try {
                    startForeground(NOTIFICATION_ID, notification);
                    return true;
                } catch (SecurityException se2) {
                    Log.e("CAPTURE", "startForeground without type denied", se2);
                    Toast.makeText(this, "Không thể chạy dịch nền: hãy bật Thông báo cho ứng dụng.", Toast.LENGTH_LONG).show();
                    return false;
                } catch (RuntimeException re) {
                    Log.e("CAPTURE", "startForeground without type failed", re);
                    Toast.makeText(this, "Không thể chạy dịch nền lúc này. Mở app và thử lại.", Toast.LENGTH_LONG).show();
                    return false;
                }
            } catch (RuntimeException re) {
                Log.e("CAPTURE", "startForeground failed", re);
                Toast.makeText(this, "Không thể chạy dịch nền lúc này. Mở app và thử lại.", Toast.LENGTH_LONG).show();
                return false;
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification);
                return true;
            } catch (SecurityException se) {
                Log.e("CAPTURE", "startForeground denied", se);
                Toast.makeText(this, "Không thể chạy dịch nền: hãy bật Thông báo cho ứng dụng.", Toast.LENGTH_LONG).show();
                return false;
            } catch (RuntimeException re) {
                Log.e("CAPTURE", "startForeground failed", re);
                Toast.makeText(this, "Không thể chạy dịch nền lúc này. Mở app và thử lại.", Toast.LENGTH_LONG).show();
                return false;
            }
        }
    }

    private void promptUserToGrantCapturePermission() {
        try {
            Intent helper = new Intent(this, CaptureHelperActivity.class);
            helper.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(helper);
            return;
        } catch (Exception e) {
            Log.e("CAPTURE", "Failed to start CaptureHelperActivity; falling back to notification", e);
        }

        // Fallback: show a notification action to open the permission activity.
        try {
            Intent activityIntent = new Intent(this, CaptureHelperActivity.class);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pending = PendingIntent.getActivity(this, 3001, activityIntent, flags);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Cần cấp quyền chụp màn hình")
                    .setContentText("Chạm để mở hộp thoại Cho phép chụp màn hình")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .addAction(android.R.drawable.ic_menu_camera, "Cấp quyền", pending)
                    .build();

            NotificationManagerCompat.from(this).notify(3002, notification);
        } catch (Exception ignored) {
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

        // Create capture pipeline
        ensureVirtualDisplay();

        Log.d("CAPTURE", "VirtualDisplay created: " + (virtualDisplay != null));
    }

    private void ensureVirtualDisplay() {
        if (mediaProjection == null) {
            throw new IllegalStateException("MediaProjection is null");
        }

        // Android 14+ requires registering a callback before starting capture / creating VirtualDisplay.
        ensureProjectionCallbackRegistered();

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
            try {
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
            } catch (SecurityException se) {
                // Seen on Android 14+/OEM ROMs: "Cannot create VirtualDisplay with non-current MediaProjection".
                Log.e("CAPTURE", "createVirtualDisplay denied; projection is likely stale/non-current", se);
                stopProjectionSession();
                promptUserToGrantCapturePermission();
                throw se;
            }
        }
    }

    private void ensureProjectionCallbackRegistered() {
        if (mediaProjection == null) return;

        // IMPORTANT: create callback once and register it before creating VirtualDisplay.
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

        try {
            mediaProjection.registerCallback(projectionCallback, handler);
        } catch (IllegalStateException ise) {
            // Projection may already be stopped or callback already registered; ignore.
            Log.w("CAPTURE", "registerCallback failed/ignored: " + ise.getMessage());
        } catch (Exception e) {
            Log.w("CAPTURE", "registerCallback failed/ignored", e);
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
        captureExecutor.execute(this::captureScreenInternal);
    }

    private void captureScreenInternal() {
        if (imageReader == null) {
            Log.e("CAPTURE", "imageReader is null");
            handler.post(this::promptUserToGrantCapturePermission);
            return;
        }

        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                Log.w("CAPTURE", "Image is null, retrying...");
                nullImageRetries++;
                if (nullImageRetries >= MAX_NULL_IMAGE_RETRIES) {
                    Log.w("CAPTURE", "Too many null images; session likely dead. Resetting projection.");
                    nullImageRetries = 0;
                    handler.post(() -> {
                        stopProjectionSession();
                        promptUserToGrantCapturePermission();
                    });
                    return;
                }

                handler.postDelayed(this::captureScreen, 300);
                return;
            }

            nullImageRetries = 0;

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
                handler.post(() -> Toast.makeText(this, "Không thể xử lý ảnh màn hình", Toast.LENGTH_SHORT).show());
                return;
            } finally {
                image.close();
                if (bitmap != null && bitmap != croppedBitmap) {
                    bitmap.recycle();
                }
            }

            final int sourceImageWidth = screenWidth;
            final int sourceImageHeight = screenHeight;

            // Downscale for OCR to reduce latency; scale bounding boxes back for overlay.
            final float[] boxScale = new float[]{1f, 1f};
            final Bitmap bitmapForOcr = downscaleForOcr(croppedBitmap, boxScale);
            if (bitmapForOcr != croppedBitmap && croppedBitmap != null && !croppedBitmap.isRecycled()) {
                croppedBitmap.recycle();
            }

            textRecognizer.recognizeLines(bitmapForOcr, captureExecutor, new TextRecognizer.OnLinesRecognizedListener() {
                @Override
                public void onSuccess(List<OcrLine> lines) {
                    if (bitmapForOcr != null && !bitmapForOcr.isRecycled()) {
                        bitmapForOcr.recycle();
                    }

                    if (lines == null || lines.isEmpty()) {
                        handler.post(() -> Toast.makeText(ScreenCaptureService.this, "Không tìm thấy văn bản nào", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    List<OcrLine> filteredLines = new ArrayList<>();
                    List<String> lineTexts = new ArrayList<>();
                    for (OcrLine l : lines) {
                        if (l == null) continue;
                        if (l.text == null) continue;
                        String t = l.text.trim();
                        if (t.isEmpty()) continue;
                        if (l.boundingBox == null) continue;
                        if (l.boundingBox.width() <= 0 || l.boundingBox.height() <= 0) continue;

                        android.graphics.Rect box = l.boundingBox;
                        if (boxScale[0] != 1f || boxScale[1] != 1f) {
                            box = scaleRect(box, boxScale[0], boxScale[1], sourceImageWidth, sourceImageHeight);
                        }

                        filteredLines.add(new OcrLine(t, box));
                        lineTexts.add(t);
                    }

                    if (filteredLines.isEmpty()) {
                        handler.post(() -> Toast.makeText(ScreenCaptureService.this, "Không tìm thấy văn bản nào", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    SupportedLang source = LanguageManager.getSourceLang();
                    SupportedLang target = LanguageManager.getTargetLang();

                    // Best-effort warmup (in case user changed languages while service is alive).
                    onDeviceTranslator.preloadModels(source, target);

                    onDeviceTranslator.translateLines(lineTexts, source, target, new OnDeviceTranslator.TranslationBatchCallback() {
                        @Override
                        public void onSuccess(List<String> translatedLines) {
                            if (translatedLines == null || translatedLines.isEmpty()) {
                                handler.post(() -> Toast.makeText(ScreenCaptureService.this, "Không có kết quả dịch", Toast.LENGTH_SHORT).show());
                                return;
                            }

                            int count = Math.min(filteredLines.size(), translatedLines.size());
                            List<TranslatedLine> overlayLines = new ArrayList<>(count);
                            for (int i = 0; i < count; i++) {
                                String translated = translatedLines.get(i);
                                if (translated == null) translated = "";
                                overlayLines.add(new TranslatedLine(translated, filteredLines.get(i).boundingBox));
                            }

                            handler.post(() -> {
                                try {
                                    translationOverlay.showInPlace(overlayLines, sourceImageWidth, sourceImageHeight);
                                } catch (Exception e) {
                                    String preview = overlayLines.get(0).translatedText;
                                    Toast.makeText(ScreenCaptureService.this,
                                            preview.substring(0, Math.min(preview.length(), 150)),
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        }

                        @Override
                        public void onFailure(Exception e) {
                            Log.e("CAPTURE", "Translation failed", e);
                            handler.post(() -> Toast.makeText(ScreenCaptureService.this, "Lỗi dịch: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    if (bitmapForOcr != null && !bitmapForOcr.isRecycled()) {
                        bitmapForOcr.recycle();
                    }
                    Log.e("CAPTURE", "OCR failed", e);
                    handler.post(() -> Toast.makeText(ScreenCaptureService.this, "Lỗi OCR: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            });
        } catch (Exception e) {
            Log.e("CAPTURE", "captureScreen failed", e);
            handler.post(() -> Toast.makeText(this, "Chụp màn hình thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Returns a bitmap potentially downscaled for OCR.
     * boxScale[0]=scaleX and boxScale[1]=scaleY to map OCR bounding boxes back to source coords.
     */
    private Bitmap downscaleForOcr(@NonNull Bitmap source, @NonNull float[] boxScale) {
        int w = source.getWidth();
        int h = source.getHeight();
        if (w <= 0 || h <= 0) return source;

        if (w <= MAX_OCR_WIDTH_PX) {
            boxScale[0] = 1f;
            boxScale[1] = 1f;
            return source;
        }

        float ratio = (float) MAX_OCR_WIDTH_PX / (float) w;
        int newW = MAX_OCR_WIDTH_PX;
        int newH = Math.max(1, Math.round(h * ratio));

        Bitmap scaled = Bitmap.createScaledBitmap(source, newW, newH, true);
        boxScale[0] = (float) w / (float) newW;
        boxScale[1] = (float) h / (float) newH;
        return scaled;
    }

    private android.graphics.Rect scaleRect(
            @NonNull android.graphics.Rect rect,
            float scaleX,
            float scaleY,
            int maxW,
            int maxH
    ) {
        int left = Math.round(rect.left * scaleX);
        int top = Math.round(rect.top * scaleY);
        int right = Math.round(rect.right * scaleX);
        int bottom = Math.round(rect.bottom * scaleY);

        left = clamp(left, 0, Math.max(0, maxW));
        right = clamp(right, 0, Math.max(0, maxW));
        top = clamp(top, 0, Math.max(0, maxH));
        bottom = clamp(bottom, 0, Math.max(0, maxH));

        return new android.graphics.Rect(left, top, right, bottom);
    }

    private int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        // Do NOT stop projection session here, otherwise user will be prompted again next time.
        // Also: do NOT release VirtualDisplay/ImageReader here, otherwise next tap would need
        // to create a new VirtualDisplay, which some ROMs forbids and throws SecurityException.
        try {
            captureExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
