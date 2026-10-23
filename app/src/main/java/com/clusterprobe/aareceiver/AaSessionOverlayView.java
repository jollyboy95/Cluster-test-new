package com.clusterprobe.aareceiver;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * v0.40: the exact same SurfaceView + MediaCodec decode pipeline as AndroidAutoSessionActivity,
 * repackaged as a plain View instead of an Activity, so it can be hosted inside
 * OverlayManager's existing TYPE_APPLICATION_OVERLAY window on a specific display (see
 * OverlayManager.showAaSession()) - the same window type ClusterProbe's own dial overlay already
 * uses successfully on display 2, and the same window type the SmartSplit/Kafo Themes APK
 * inspection found both of them using for their own "send app to the cluster" feature.
 *
 * This sidesteps ActivityOptions.setLaunchDisplayId()'s launch-on-display restriction (and the
 * whole Shizuku/ShellUserService detour built for it in v0.37/v0.38) entirely for OUR OWN video -
 * an overlay window is added directly via WindowManager.addView() on a display-scoped Context,
 * never launched as a separate Activity task, so it was never subject to that restriction. No
 * Shizuku, no extra permission beyond SYSTEM_ALERT_WINDOW, which this project already declares
 * and already uses successfully for the dial overlay.
 *
 * AndroidAutoSessionActivity itself is kept, unmodified, for the default-display case (when
 * AaWirelessManualConnect.launchOnClusterDisplay is off) - this class is additive, not a
 * replacement, and the two share no code beyond both calling into AaSessionRunner.
 */
public class AaSessionOverlayView extends FrameLayout implements SurfaceHolder.Callback {

    private static final String TAG = "AaSessionOverlayView";

    /** Same default "480p"-class automotive resolution AndroidAutoSessionActivity uses - not yet
     *  matched to the cluster display's real dimensions. */
    private static final int DECODE_WIDTH = 800;
    private static final int DECODE_HEIGHT = 480;

    private final TextView statusView;
    private final SurfaceView surfaceView;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AaSessionRunner sessionRunner;
    private Thread sessionThread;
    private MediaCodec decoder;
    private final LinkedBlockingQueue<byte[]> pendingVideo = new LinkedBlockingQueue<>();
    private volatile boolean decoderRunning = false;
    private Thread decoderThread;

    /**
     * v0.42: the overlay's own statusView can only show ONE line at a time, overwriting itself on
     * every AaSessionRunner status update - a real limitation found on first on-car use, where a
     * fast-moving handshake (version exchange, TLS, auth, service discovery, channel open, AV
     * setup) could all fly past in milliseconds and be invisible, leaving only whichever message
     * happened to land last (often just "video decoder started", since that's wired to
     * SurfaceView.surfaceCreated() independently of session progress) with no way to tell whether
     * the session is actually stuck there or simply hasn't been given long enough. ExternalLog lets
     * the caller (AaWirelessManualConnect) also mirror every status line to MainActivity's own
     * scrolling, readable log panel on the main screen - so the full sequence is visible there
     * without needing to photograph the reflective cluster display after every status change.
     */
    public interface ExternalLog { void onLine(String msg); }

    public AaSessionOverlayView(Context context, Socket socket, ExternalLog externalLog) {
        super(context);

        surfaceView = new SurfaceView(context);
        addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(this);

        statusView = new TextView(context);
        statusView.setTextColor(Color.parseColor("#e3c29b"));
        statusView.setBackgroundColor(Color.parseColor("#AA000000"));
        statusView.setPadding(24, 24, 24, 24);
        statusView.setText("Waiting for connection...");
        addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        startSession(socket, externalLog);
    }

    private void startSession(Socket socket, ExternalLog externalLog) {
        sessionRunner = new AaSessionRunner();
        sessionThread = new Thread(() -> sessionRunner.run(socket, new AaSessionRunner.Listener() {
            @Override public void onStatus(String msg) {
                Log.i(TAG, msg);
                setStatus(msg);
                if (externalLog != null) externalLog.onLine(msg);
            }
            @Override public void onVideoData(byte[] h264) {
                pendingVideo.offer(h264);
            }
            @Override public void onSessionEnded(String reason) {
                Log.i(TAG, "session ended: " + reason);
                setStatus("Session ended: " + reason);
                if (externalLog != null) externalLog.onLine("Session ended: " + reason);
            }
        }), "aa-session-runner-overlay");
        sessionThread.setDaemon(true);
        sessionThread.start();
    }

    private void setStatus(String msg) {
        main.post(() -> statusView.setText(msg));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startDecoder(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopDecoder();
    }

    private void startDecoder(Surface surface) {
        if (decoderRunning) return;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", DECODE_WIDTH, DECODE_HEIGHT);
            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, surface, null, 0);
            decoder.start();
            decoderRunning = true;
            decoderThread = new Thread(this::decoderLoop, "aa-h264-decoder-overlay");
            decoderThread.setDaemon(true);
            decoderThread.start();
            setStatus("Video decoder started (" + DECODE_WIDTH + "x" + DECODE_HEIGHT + ") - "
                    + "waiting for real frames...");
        } catch (Exception e) {
            Log.e(TAG, "decoder start failed", e);
            setStatus("Video decoder failed to start: " + e);
        }
    }

    private void decoderLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean gotFirstFrame = false;
        while (decoderRunning) {
            try {
                byte[] nal = pendingVideo.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (nal != null && nal.length > 0) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        java.nio.ByteBuffer buf = decoder.getInputBuffer(inIndex);
                        buf.clear();
                        buf.put(nal);
                        decoder.queueInputBuffer(inIndex, 0, nal.length, System.nanoTime() / 1000, 0);
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, 0);
                if (outIndex >= 0) {
                    decoder.releaseOutputBuffer(outIndex, true); // render straight to the Surface
                    if (!gotFirstFrame) {
                        gotFirstFrame = true;
                        setStatus("FIRST VIDEO FRAME DECODED - Android Auto is on the cluster.");
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "decoder loop error", t);
            }
        }
    }

    private void stopDecoder() {
        decoderRunning = false;
        if (decoderThread != null) {
            try { decoderThread.join(500); } catch (InterruptedException ignored) { }
        }
        if (decoder != null) {
            try { decoder.stop(); decoder.release(); } catch (Throwable ignored) { }
            decoder = null;
        }
    }

    /** Called by OverlayManager.hide() when this overlay is being torn down or replaced - stops
     *  the session and decoder, mirroring AndroidAutoSessionActivity.onDestroy(). */
    public void stopSession() {
        if (sessionRunner != null) sessionRunner.stop();
        stopDecoder();
    }
}
