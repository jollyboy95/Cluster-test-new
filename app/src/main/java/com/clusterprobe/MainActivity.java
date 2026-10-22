package com.clusterprobe;

import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Control panel. Runs on display 0 (the main head-unit screen), and drives ClusterActivity on
 * display 2 (the physical cluster). Everything here uses public Android APIs only - nothing
 * tries to touch the signature-protected GWM adapter / theme_config interface.
 */
public class MainActivity extends android.app.Activity {

    private static final int REQ_CAPTURE = 4201;
    private static final int CLUSTER_DISPLAY_ID = 2;

    private TextView log;
    private TextView themeLabel;
    private ImageView preview;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean previewLoopRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(Color.parseColor("#0b0907"));

        addSection(root, "Displays");
        addButton(root, "List displays", this::listDisplays);

        addSection(root, "Cluster theme (display " + CLUSTER_DISPLAY_ID + ")");
        themeLabel = addLabel(root, "Theme: " + DialView.theme.label);
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        addButtonTo(themeRow, "<< Prev", () -> cycleTheme(-1));
        addButtonTo(themeRow, "Next >>", () -> cycleTheme(1));
        root.addView(themeRow);

        addButton(root, "Launch on cluster (display " + CLUSTER_DISPLAY_ID + ")", this::launchOnCluster);
        addButton(root, "Stop cluster window", this::stopCluster);

        addSection(root, "Experimental: capture display content");
        addButton(root, "Test map capture (MediaProjection)", this::testCapture);
        Button toggleCapture = addButton(root, "Use captured frame in cluster: OFF", null);
        toggleCapture.setOnClickListener(v -> {
            DialView.useCaptureOverlay = !DialView.useCaptureOverlay;
            toggleCapture.setText("Use captured frame in cluster: " + (DialView.useCaptureOverlay ? "ON" : "OFF"));
            if (ClusterActivity.current != null) ClusterActivity.current.applyThemeChange();
            appendLog("Capture overlay " + (DialView.useCaptureOverlay ? "enabled" : "disabled") + " for MAP_WINDOW themes.");
        });

        preview = new ImageView(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 220);
        pp.topMargin = 16;
        preview.setLayoutParams(pp);
        preview.setBackgroundColor(Color.parseColor("#1a1613"));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview);

        addSection(root, "Log");
        log = new TextView(this);
        log.setTextColor(Color.parseColor("#d8d2c8"));
        log.setTextSize(12);
        log.setMovementMethod(new ScrollingMovementMethod());
        ScrollView logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logScroll.setLayoutParams(lp);
        logScroll.addView(log);
        root.addView(logScroll);

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);

        appendLog("ClusterProbe ready. Theme: " + DialView.theme.label);
        listDisplays();
    }

    private void cycleTheme(int dir) {
        DialView.Theme[] vals = DialView.Theme.values();
        int idx = DialView.theme.ordinal();
        idx = (idx + dir + vals.length) % vals.length;
        DialView.theme = vals[idx];
        themeLabel.setText("Theme: " + DialView.theme.label);
        if (ClusterActivity.current != null) ClusterActivity.current.applyThemeChange();
        appendLog("Theme set to " + DialView.theme.label);
    }

    private void listDisplays() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays();
        appendLog("--- Displays (" + displays.length + ") ---");
        for (Display d : displays) {
            android.graphics.Point sz = new android.graphics.Point();
            d.getRealSize(sz);
            appendLog("id=" + d.getDisplayId() + " name=\"" + d.getName() + "\" size=" + sz.x + "x" + sz.y
                    + " state=" + d.getState());
        }
    }

    private void launchOnCluster() {
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(CLUSTER_DISPLAY_ID);
            Intent i = new Intent(this, ClusterActivity.class);
            i.putExtra(ClusterActivity.EXTRA_THEME_ORDINAL, DialView.theme.ordinal());
            i.putExtra(ClusterActivity.EXTRA_USE_CAPTURE, DialView.useCaptureOverlay);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(i, opts.toBundle());
            appendLog("Launch requested on display " + CLUSTER_DISPLAY_ID + " via ActivityOptions.setLaunchDisplayId.");
        } catch (SecurityException se) {
            appendLog("Denied: " + se.getMessage()
                    + "  (this app likely lacks the permission a normal app needs to target a non-default "
                    + "display from in-process; \"am start --display " + CLUSTER_DISPLAY_ID + "\" from adb shell "
                    + "worked earlier because the shell UID has more latitude than a regular app).");
        } catch (Exception e) {
            appendLog("Launch failed: " + e);
        }
    }

    private void stopCluster() {
        if (ClusterActivity.current != null) {
            ClusterActivity.current.finish();
            appendLog("Cluster window closed.");
        } else {
            appendLog("No cluster window is currently open (from this app's point of view).");
        }
    }

    private void testCapture() {
        appendLog("Requesting MediaProjection capture - accept the system prompt if one appears.");
        CaptureHelper.requestCapture(this, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            appendLog("Capture not granted (resultCode=" + resultCode + "). Nothing started.");
            return;
        }
        CaptureHelper.start(this, resultCode, data, new CaptureHelper.FrameListener() {
            @Override public void onFrame(Bitmap bmp) {
                main.post(() -> {
                    preview.setImageBitmap(bmp);
                    float luma = CaptureHelper.averageLuma(bmp);
                    if (luma >= 0 && luma < 4f) {
                        appendLogOnce("Captured frame is nearly all black (avg luma " + luma
                                + ") - this usually means the content is protected/blanked for capture, "
                                + "or we're only mirroring an idle screen. Worth checking the preview above.");
                    }
                });
            }
            @Override public void onLog(String msg) { main.post(() -> appendLog(msg)); }
        });
        startPreviewLoopLog();
    }

    private String lastOnceMsg = "";
    private void appendLogOnce(String msg) {
        if (!msg.equals(lastOnceMsg)) { appendLog(msg); lastOnceMsg = msg; }
    }

    private void startPreviewLoopLog() {
        if (previewLoopRunning) return;
        previewLoopRunning = true;
        appendLog("Capture running. Watch the preview box above - if it shows the live head-unit "
                + "screen (or the AA/CarPlay map) that confirms what MediaProjection can actually reach here.");
    }

    private void appendLog(String msg) {
        log.append(msg + "\n");
    }

    @Override
    protected void onDestroy() {
        CaptureHelper.stop();
        super.onDestroy();
    }

    // ---- small UI helpers ----
    private TextView addLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#e3c29b"));
        tv.setTextSize(15);
        parent.addView(tv);
        return tv;
    }

    private void addSection(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#f3eee8"));
        tv.setTextSize(17);
        tv.setPadding(0, 28, 0, 8);
        tv.setGravity(Gravity.START);
        parent.addView(tv);
    }

    private Button addButton(LinearLayout parent, String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        if (onClick != null) b.setOnClickListener(v -> onClick.run());
        parent.addView(b);
        return b;
    }

    private Button addButtonTo(LinearLayout row, String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onClick.run());
        row.addView(b);
        return b;
    }
}
