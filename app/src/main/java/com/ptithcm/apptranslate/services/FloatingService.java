package com.ptithcm.apptranslate.services;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ptithcm.apptranslate.R;
import com.ptithcm.apptranslate.activities.CaptureHelperActivity;

public class FloatingService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;

    @Override
    public void onCreate() {
        super.onCreate();
        
        startForegroundService();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_button, null);

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 100;

        windowManager.addView(floatingView, params);
        setupTouchListener();
    }

    private void startForegroundService() {
        String CHANNEL_ID = "floating_service_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Floating Service Channel",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("App Dịch Màn Hình")
                .setContentText("Nút dịch đang hiển thị")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchListener() {
        floatingView.findViewById(R.id.card_view).setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        int diffX = (int) (event.getRawX() - initialTouchX);
                        int diffY = (int) (event.getRawY() - initialTouchY);
                        
                        if (Math.abs(diffX) < 10 && Math.abs(diffY) < 10) {
                            onFloatingButtonClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void onFloatingButtonClick() {
        // When an overlay service tries to start an Activity, some devices/Android versions may block it.
        // We'll try, and if it fails, fall back to a notification action.
        try {
            Intent intent = new Intent(this, CaptureHelperActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e("CAPTURE", "Failed to start CaptureHelperActivity from overlay. Falling back to notification action.", e);
            showRequestCapturePermissionNotification();
        }
    }

    private void showRequestCapturePermissionNotification() {
        String CHANNEL_ID = "floating_service_channel";

        Intent activityIntent = new Intent(this, CaptureHelperActivity.class);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pending = PendingIntent.getActivity(this, 2001, activityIntent, flags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Cần cấp quyền chụp màn hình")
                .setContentText("Chạm để mở hộp thoại Cho phép chụp màn hình")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_camera, "Cấp quyền", pending)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    // Can't post notification; at least guide the user.
                    Toast.makeText(this,
                            "Chưa có quyền thông báo. Mở app và cho phép Thông báo để dùng cách cấp quyền qua thông báo.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }

            NotificationManagerCompat.from(this).notify(2002, notification);
            Toast.makeText(this, "Hãy bấm thông báo để cấp quyền chụp màn hình.", Toast.LENGTH_LONG).show();
        } catch (SecurityException se) {
            Log.e("CAPTURE", "Missing POST_NOTIFICATIONS permission", se);
            Toast.makeText(this,
                    "Chưa có quyền thông báo. Vào Cài đặt > Thông báo để bật, rồi thử lại.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) windowManager.removeView(floatingView);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}