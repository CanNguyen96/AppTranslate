package com.ptithcm.apptranslate.activities;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ptithcm.apptranslate.R;
import com.ptithcm.apptranslate.services.FloatingService;
import com.ptithcm.apptranslate.services.ScreenCaptureService;
import com.ptithcm.apptranslate.utils.PermissionUtils;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus;
    private Button btnRequestPermission;
    private Button btnStartService;
    private Button btnStopApp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnRequestPermission = findViewById(R.id.btnRequestPermission);
        btnStartService = findViewById(R.id.btnStartService);
        btnStopApp = findViewById(R.id.btnStopApp);

        btnRequestPermission.setOnClickListener(v -> {
            PermissionUtils.requestOverlayPermission(this);
        });

        btnStartService.setOnClickListener(v -> {
            startTranslationService();
        });

        btnStopApp.setOnClickListener(v -> stopAppAndBackground());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void updatePermissionStatus() {
        if (PermissionUtils.hasOverlayPermission(this)) {
            tvStatus.setText("Trạng thái quyền: Đã cấp");
            tvStatus.setTextColor(Color.GREEN);
            btnRequestPermission.setVisibility(View.GONE);
            btnStartService.setEnabled(true);
        } else {
            tvStatus.setText("Trạng thái quyền: Chưa cấp (Cần Overlay)");
            tvStatus.setTextColor(Color.RED);
            btnRequestPermission.setVisibility(View.VISIBLE);
            btnStartService.setEnabled(false);
        }
    }

    private void startTranslationService() {
        if (PermissionUtils.hasOverlayPermission(this)) {
            Intent intent = new Intent(this, FloatingService.class);
            ContextCompat.startForegroundService(this, intent);
            finish(); // Đóng activity để quay lại màn hình trước đó
        } else {
            Toast.makeText(this, "Vui lòng cấp quyền Overlay trước", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAppAndBackground() {
        // 1) Stop floating overlay service (removes the bubble)
        try {
            stopService(new Intent(this, FloatingService.class));
        } catch (Exception ignored) {
        }

        // 2) Stop screen capture + release MediaProjection session (so nothing keeps running)
        try {
            Intent stopProjection = new Intent(this, ScreenCaptureService.class);
            stopProjection.setAction(ScreenCaptureService.ACTION_STOP_PROJECTION);
            ContextCompat.startForegroundService(this, stopProjection);
        } catch (Exception ignored) {
        }

        try {
            stopService(new Intent(this, ScreenCaptureService.class));
        } catch (Exception ignored) {
        }

        // 3) Clear any foreground notifications (best-effort)
        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(this);
            nm.cancel(1);    // FloatingService foreground notification
            nm.cancel(2);    // ScreenCaptureService foreground notification
            nm.cancel(2002); // FloatingService request-permission notification
        } catch (Exception ignored) {
        }

        // 4) Exit app UI (Android may keep process cached, but services are stopped)
        finishAndRemoveTask();
    }
}