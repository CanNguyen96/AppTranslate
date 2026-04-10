package com.ptithcm.apptranslate.capture;

import android.content.Intent;

/**
 * In-memory store for the most recent MediaProjection consent token.
 *
 * Notes:
 * - This token only lives as long as the app process lives.
 * - It's intended to fix cases where the service is (re)started without extras.
 */
public final class MediaProjectionTokenStore {
    private MediaProjectionTokenStore() {}

    private static volatile int resultCode = -1;
    private static volatile Intent resultData;

    public static void save(int rc, Intent data) {
        resultCode = rc;
        resultData = data;
    }

    public static int getResultCode() {
        return resultCode;
    }

    public static Intent getResultData() {
        return resultData;
    }

    public static boolean hasToken() {
        return resultCode != -1 && resultData != null;
    }

    public static void clear() {
        resultCode = -1;
        resultData = null;
    }
}

