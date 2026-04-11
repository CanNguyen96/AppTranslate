package com.ptithcm.apptranslate.activities;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ptithcm.apptranslate.R;
import com.ptithcm.apptranslate.services.FloatingService;
import com.ptithcm.apptranslate.services.ScreenCaptureService;
import com.ptithcm.apptranslate.translate.LanguageManager;
import com.ptithcm.apptranslate.translate.OnDeviceTranslator;
import com.ptithcm.apptranslate.translate.SupportedLang;
import com.ptithcm.apptranslate.utils.PermissionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_POST_NOTIFICATIONS = 501;

    private TextView tvStatus;
    private Button btnRequestPermission;
    private Button btnStartService;
    private Button btnStopApp;

    private Spinner spSourceLang;
    private Spinner spTargetLang;

    private List<SupportedLang> sourceOptions;
    private List<SupportedLang> targetOptions;

    private OnDeviceTranslator translationModelPreloader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        btnRequestPermission = findViewById(R.id.btnRequestPermission);
        btnStartService = findViewById(R.id.btnStartService);
        btnStopApp = findViewById(R.id.btnStopApp);

        spSourceLang = findViewById(R.id.spSourceLang);
        spTargetLang = findViewById(R.id.spTargetLang);

        setupLanguagePickers();

        requestPostNotificationsIfNeeded();

        btnRequestPermission.setOnClickListener(v -> {
            PermissionUtils.requestOverlayPermission(this);
        });

        btnStartService.setOnClickListener(v -> {
            startTranslationService();
        });

        btnStopApp.setOnClickListener(v -> stopAppAndBackground());
    }

    private void requestPostNotificationsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                            this,
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                            REQ_POST_NOTIFICATIONS
                    );
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void setupLanguagePickers() {
        // Load saved language selection (if any)
        LanguageManager.init(this);
        translationModelPreloader = new OnDeviceTranslator(getApplicationContext());

        sourceOptions = Arrays.asList(
                SupportedLang.AUTO,
                SupportedLang.EN,
                SupportedLang.JA,
                SupportedLang.KO,
                SupportedLang.VI
        );

        // Target must not be AUTO
        targetOptions = Arrays.asList(
                SupportedLang.VI,
                SupportedLang.EN,
                SupportedLang.JA,
                SupportedLang.KO
        );

        ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                toDisplayNames(sourceOptions)
        );
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSourceLang.setAdapter(sourceAdapter);

        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                toDisplayNames(targetOptions)
        );
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTargetLang.setAdapter(targetAdapter);

        SupportedLang currentSource = LanguageManager.getSourceLang();
        SupportedLang currentTarget = LanguageManager.getTargetLang();

        // Warm up translation models early to reduce perceived latency.
        translationModelPreloader.preloadModels(currentSource, currentTarget);

        spSourceLang.setSelection(Math.max(0, sourceOptions.indexOf(currentSource)));
        int targetIndex = targetOptions.indexOf(currentTarget);
        spTargetLang.setSelection(targetIndex >= 0 ? targetIndex : 0);

        spSourceLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= sourceOptions.size()) return;
                SupportedLang selected = sourceOptions.get(position);
                LanguageManager.setSourceLang(MainActivity.this, selected);
                if (translationModelPreloader != null) {
                    translationModelPreloader.preloadModels(LanguageManager.getSourceLang(), LanguageManager.getTargetLang());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        spTargetLang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= targetOptions.size()) return;
                SupportedLang selected = targetOptions.get(position);
                LanguageManager.setTargetLang(MainActivity.this, selected);
                if (translationModelPreloader != null) {
                    translationModelPreloader.preloadModels(LanguageManager.getSourceLang(), LanguageManager.getTargetLang());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });
    }

    private static List<String> toDisplayNames(List<SupportedLang> langs) {
        List<String> out = new ArrayList<>(langs.size());
        for (SupportedLang l : langs) {
            out.add(l.displayName);
        }
        return out;
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