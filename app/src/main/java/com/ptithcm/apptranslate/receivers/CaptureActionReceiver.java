package com.ptithcm.apptranslate.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.ptithcm.apptranslate.activities.CaptureHelperActivity;
import com.ptithcm.apptranslate.notifications.NotificationActions;

public class CaptureActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (NotificationActions.ACTION_REQUEST_MEDIA_PROJECTION.equals(action)) {
            Log.d("CAPTURE", "Notification action clicked: request MediaProjection");
            Intent start = new Intent(context, CaptureHelperActivity.class);
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(start);
        }
    }
}

