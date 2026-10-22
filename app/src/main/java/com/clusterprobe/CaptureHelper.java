package com.clusterprobe;

import android.app.Activity;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.nio.ByteBuffer;

/**
 * Experimental: wraps the PUBLIC MediaProjection API to see what it actually hands us on this
 * head unit. There is no public API to request a specific displayId (e.g. "give me display 1,
 * the Android Auto map surface") - MediaProjection always mirrors whatever the system picker /
 * platform decides to share, which on a phone is "the whole screen" (display 0). Whether that
 * means display 0 here, or something else, whether it's blank/black (common for DRM/secure
 * windows), or whether it's refused entirely, is exactly what this test is for. Treat every
 * result as a data point, not an assumption.
 */
public class CaptureHelper {

    public interface FrameListener { void onFrame(Bitmap bmp); void onLog(String msg); }

    private static CaptureHelper instance;
    public static CaptureHelper get() { return instance; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private FrameListener listener;
    private int w, h;
    public volatile Bitmap latestBitmap;

    /** @return null if the request couldn't even be made (e.g. no MediaProjection service on
     *  this build) - the caller should check and log rather than assume it always starts. */
    public static String requestCapture(Activity act, int requestCode) {
        MediaProjectionManager mpm;
        try {
            mpm = (MediaProjectionManager) act.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        } catch (Throwable t) {
            return "getSystemService(MEDIA_PROJECTION_SERVICE) threw: " + t;
        }
        if (mpm == null) {
            return "MEDIA_PROJECTION_SERVICE is not available on this build (getSystemService returned null).";
        }
        android.content.Intent intent;
        try {
            intent = mpm.createScreenCaptureIntent();
        } catch (Throwable t) {
            return "createScreenCaptureIntent() threw: " + t;
        }
        if (intent == null) {
            return "createScreenCaptureIntent() returned null.";
        }
        try {
            act.startActivityForResult(intent, requestCode);
        } catch (Throwable t) {
            return "startActivityForResult() threw: " + t
                    + "  (no activity on this build may handle the capture-consent screen).";
        }
        return null; // success
    }

    /** Call from onActivityResult once the user grants the capture prompt (resultCode == RESULT_OK). */
    public static CaptureHelper start(Activity act, int resultCode, android.content.Intent data, FrameListener listener) {
        stop();
        instance = new CaptureHelper();
        instance.listener = listener;
        MediaProjectionManager mpm = (MediaProjectionManager) act.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        instance.projection = mpm.getMediaProjection(resultCode, data);
        if (instance.projection == null) {
            listener.onLog("getMediaProjection() returned null - capture not actually granted.");
            instance = null;
            return null;
        }

        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        instance.w = dm.widthPixels;
        instance.h = dm.heightPixels;
        int dpi = dm.densityDpi;

        instance.reader = ImageReader.newInstance(instance.w, instance.h, android.graphics.PixelFormat.RGBA_8888, 2);
        instance.virtualDisplay = instance.projection.createVirtualDisplay(
                "ClusterProbeCapture", instance.w, instance.h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                instance.reader.getSurface(), null, instance.main);

        listener.onLog("Capture started: mirroring " + instance.w + "x" + instance.h + " @" + dpi + "dpi"
                + " (this is whatever the platform decided to share - not necessarily display 1).");

        instance.reader.setOnImageAvailableListener(ir -> instance.onImage(ir), instance.main);
        return instance;
    }

    private void onImage(ImageReader ir) {
        Image img = null;
        try {
            img = ir.acquireLatestImage();
            if (img == null) return;
            Image.Plane plane = img.getPlanes()[0];
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int rowPadding = rowStride - pixelStride * w;
            Bitmap bmp = Bitmap.createBitmap(w + rowPadding / Math.max(1, pixelStride), h, Bitmap.Config.ARGB_8888);
            ByteBuffer buf = plane.getBuffer();
            bmp.copyPixelsFromBuffer(buf);
            latestBitmap = bmp;
            if (listener != null) listener.onFrame(bmp);
        } catch (Exception e) {
            if (listener != null) listener.onLog("Frame decode error: " + e);
        } finally {
            if (img != null) img.close();
        }
    }

    /** Quick heuristic: average luminance of a downsampled strip, to flag an all-black/blank capture. */
    public static float averageLuma(Bitmap bmp) {
        if (bmp == null) return -1;
        int w = bmp.getWidth(), h = bmp.getHeight();
        int stepX = Math.max(1, w / 40), stepY = Math.max(1, h / 40);
        long sum = 0; int n = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int p = bmp.getPixel(x, y);
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                sum += (r + g + b) / 3;
                n++;
            }
        }
        return n == 0 ? -1 : (float) sum / n;
    }

    public static void stop() {
        if (instance == null) return;
        try { if (instance.virtualDisplay != null) instance.virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (instance.reader != null) instance.reader.close(); } catch (Exception ignored) {}
        try { if (instance.projection != null) instance.projection.stop(); } catch (Exception ignored) {}
        instance = null;
    }
}
