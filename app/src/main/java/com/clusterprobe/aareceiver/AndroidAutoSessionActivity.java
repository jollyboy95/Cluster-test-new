package com.clusterprobe.aareceiver;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
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
 * The real, visible window for an Android Auto session - replaces this project's earlier pattern
 * of wireless/USB connection "probes" that logged a result and closed the socket. Takes an
 * already-connected Socket (see AaWirelessManualConnect, which hands off to this instead of
 * closing its test connection), runs the full session via AaSessionRunner on a background thread,
 * and decodes+renders whatever raw H.264 arrives onto a SurfaceView with android.media.MediaCodec.
 *
 * Deliberately on the DEFAULT display for this increment, not Display 2 - per the project's own
 * stated roadmap ("connect first and see Android Auto, THEN project it to Display 2, THEN build
 * the dash cluster on top"), getting a real decoded frame on screen at all is the milestone this
 * class exists for. Launching it on a specific physical display (ActivityOptions.setLaunchDisplayId,
 * already used elsewhere in this project for the cluster/HUD windows) is the next step once this
 * is confirmed working, not before.
 *
 * MediaCodec is fed raw Annex-B H.264 directly (SPS/PPS/IDR/slice NALs as the phone sends them,
 * only the AV channel's own 8-byte timestamp prefix stripped by AaSessionRunner) - the standard,
 * widely-used approach for this exact kind of elementary-stream decode (no separate CSD-0/CSD-1
 * extraction attempted; Android's video/avc decoder accepts inline parameter sets in the stream).
 * Uses a default 800x480 format (a common "480p"-class automotive resolution, matching the
 * VideoResolution._480p this project advertises in ServiceDiscoveryResponse) - NOT yet matched to
 * this head unit's actual Display 2 dimensions, since that match only matters once video is
 * routed there, the next phase.
 */
public class AndroidAutoSessionActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "AaSessionActivity";

    /** Set by whoever launches this Activity (e.g. AaWirelessManualConnect, right after a
     *  successful TCP connect) BEFORE calling startActivity() - a static handoff rather than a
     *  Parcelable extra, since a live connected Socket can't be put in an Intent. Consumed once in
     *  onCreate() and cleared, so a second launch without setting this again fails loudly instead
     *  of silently reusing a stale/closed socket. */
    public static volatile Socket pendingSocket;

    private TextView statusView;
    private SurfaceView surfaceView;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AaSessionRunner sessionRunner;
    private Thread sessionThread;
    private MediaCodec decoder;
    private final LinkedBlockingQueue<byte[]> pendingVideo = new LinkedBlockingQueue<>();
    private volatile boolean decoderRunning = false;
    private Thread decoderThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(this);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#e3c29b"));
        statusView.setBackgroundColor(Color.parseColor("#AA000000"));
        statusView.setPadding(24, 24, 24, 24);
        statusView.setText("Waiting for connection...");
        root.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        Socket socket = pendingSocket;
        pendingSocket = null;
        if (socket == null) {
            setStatus("No connected socket was handed to this screen - launched incorrectly.");
            return;
        }

        sessionRunner = new AaSessionRunner();
        sessionThread = new Thread(() -> sessionRunner.run(socket, new AaSessionRunner.Listener() {
            @Override public void onStatus(String msg) {
                Log.i(TAG, msg);
                setStatus(msg);
            }
            @Override public void onVideoData(byte[] h264) {
                pendingVideo.offer(h264);
            }
            @Override public void onSessionEnded(String reason) {
                Log.i(TAG, "session ended: " + reason);
                setStatus("Session ended: " + reason);
            }
        }), "aa-session-runner");
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

    /** Default "480p"-class automotive resolution, matching the VideoResolution._480p this
     *  project advertises in ServiceDiscoveryResponse - not yet matched to Display 2's real
     *  dimensions (see class doc comment). */
    private static final int DECODE_WIDTH = 800;
    private static final int DECODE_HEIGHT = 480;

    private void startDecoder(Surface surface) {
        if (decoderRunning) return;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", DECODE_WIDTH, DECODE_HEIGHT);
            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, surface, null, 0);
            decoder.start();
            decoderRunning = true;
            decoderThread = new Thread(this::decoderLoop, "aa-h264-decoder");
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
                        setStatus("FIRST VIDEO FRAME DECODED - Android Auto is on screen.");
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

    @Override
    protected void onDestroy() {
        if (sessionRunner != null) sessionRunner.stop();
        stopDecoder();
        super.onDestroy();
    }
}
