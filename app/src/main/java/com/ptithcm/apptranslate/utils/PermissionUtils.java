package com.ptithcm.apptranslate.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

public class PermissionUtils {

    // Kiểm tra xem đã có quyền hiển thị trên ứng dụng khác chưa
    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; // Các bản Android cũ tự động có quyền
    }

    // Mở màn hình cài đặt để người dùng cấp quyền Overlay
    public static void requestOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        }
    }
}