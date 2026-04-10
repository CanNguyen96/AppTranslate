package com.ptithcm.apptranslate.capture;

import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.projection.MediaProjection;

/**
 * Holds the active MediaProjection session in-memory.
 *
 * Why:
 * - Storing the consent Intent is not enough to avoid the system prompt.
 * - To skip the prompt, keep a single MediaProjection alive and reuse it.
 * - Some ROMs disallow calling createVirtualDisplay multiple times for one session,
 *   so we also keep the VirtualDisplay + ImageReader alive.
 *
 * Limitations:
 * - This is process-memory only. If the process dies, user must grant again.
 */
public final class MediaProjectionSession {
    private MediaProjectionSession() {}

    private static volatile MediaProjection projection;
    private static volatile VirtualDisplay virtualDisplay;
    private static volatile ImageReader imageReader;

    public static void set(MediaProjection p) {
        projection = p;
    }

    public static MediaProjection get() {
        return projection;
    }

    public static boolean isActive() {
        return projection != null;
    }

    public static void setVirtualDisplay(VirtualDisplay vd) {
        virtualDisplay = vd;
    }

    public static VirtualDisplay getVirtualDisplay() {
        return virtualDisplay;
    }

    public static void setImageReader(ImageReader reader) {
        imageReader = reader;
    }

    public static ImageReader getImageReader() {
        return imageReader;
    }

    public static void clear() {
        projection = null;
        virtualDisplay = null;
        imageReader = null;
    }
}
