package com.ptithcm.apptranslate.activities;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.ptithcm.apptranslate.capture.MediaProjectionSession;
import com.ptithcm.apptranslate.capture.MediaProjectionTokenStore;
import com.ptithcm.apptranslate.services.ScreenCaptureService;

public class CaptureHelperActivity extends ComponentActivity {

    private ActivityResultLauncher<Intent> captureLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If we already have an active projection session, skip the consent UI.
        if (MediaProjectionSession.isActive()) {
            Log.d("CAPTURE", "MediaProjection session active -> skip consent UI and trigger capture");
            Intent serviceIntent = new Intent(CaptureHelperActivity.this, ScreenCaptureService.class);
            serviceIntent.setAction(ScreenCaptureService.ACTION_CAPTURE_ONCE);
            ContextCompat.startForegroundService(CaptureHelperActivity.this, serviceIntent);
            finish();
            return;
        }

        // Ensure this activity is actually brought to the foreground when started from an overlay service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        captureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        final int resultCode = result.getResultCode();
                        final Intent data = result.getData();

                        Log.d("CAPTURE", "MediaProjection onActivityResult: resultCode=" + resultCode + ", data=" + (data != null) + ", dataExtras=" + (data != null ? data.getExtras() : null));

                        if (resultCode == RESULT_OK && data != null) {
                            Log.d("CAPTURE", "User granted screen capture permission");

                            MediaProjectionTokenStore.save(resultCode, data);

                            Intent serviceIntent = new Intent(CaptureHelperActivity.this, ScreenCaptureService.class);
                            serviceIntent.setAction(ScreenCaptureService.ACTION_START_PROJECTION);
                            serviceIntent.putExtra("RESULT_CODE", resultCode);
                            serviceIntent.putExtra("RESULT_DATA", data);

                            Log.d("CAPTURE", "Starting ScreenCaptureService with extras: RESULT_CODE=" + resultCode + ", RESULT_DATA=" + (data != null));
                            ContextCompat.startForegroundService(CaptureHelperActivity.this, serviceIntent);
                        } else {
                            Log.w("CAPTURE", "User denied/canceled screen capture permission (resultCode=" + resultCode + ")");
                            Toast.makeText(CaptureHelperActivity.this,
                                    "Chưa cấp quyền chụp màn hình (hãy bấm Cho phép trong hộp thoại)",
                                    Toast.LENGTH_SHORT).show();
                        }

                        finish();
                    }
                }
        );

        MediaProjectionManager mpManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpManager != null) {
            // Launch consent UI
            captureLauncher.launch(mpManager.createScreenCaptureIntent());
        } else {
            Toast.makeText(this, "Thiết bị không hỗ trợ MediaProjection", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}