package com.clusterprobe;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Tries three ways of putting a view on another display and logs exactly what happens.
 * Display id defaults to 2 (where SmartSplit sees the cluster) but check "List displays" first.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ClusterProbe";
    private EditText idField;
    private TextView logView;
    private ScrollView scroll;

    private WindowManager overlayWm;
    private View overlayView;
    private Presentation presentation;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        idField = new EditText(this);
        idField.setInputType(InputType.TYPE_CLASS_NUMBER);
        idField.setText("2");
        idField.setHint("Display id");
        root.addView(idField);

        root.addView(button("H7 THEME PREVIEW (6 CONCEPTS)", v -> startActivity(new Intent(this, ThemePreviewActivity.class))));
        root.addView(button("1. List displays", v -> listDisplays()));
        root.addView(button("2. Method A: overlay window", v -> showOverlay()));
        root.addView(button("NEW: MAP-FIRST PREVIEW (A) - PARKED ONLY", v -> showMapFirst()));
        root.addView(button("3. Method B: Presentation", v -> showPresentation()));
        root.addView(button("4. Method C: launch activity on display", v -> launchActivity()));
        final Button maskBtn = button("Black mask with map oval: ON", null);
        maskBtn.setOnClickListener(v -> {
            DialView.useMask = !DialView.useMask;
            maskBtn.setText("Black mask with map oval: " + (DialView.useMask ? "ON" : "OFF"));
        });
        root.addView(maskBtn);
        final Button solidBtn = button("Dials: SOLID", null);
        solidBtn.setOnClickListener(v -> {
            DialView.solidDials = !DialView.solidDials;
            solidBtn.setText("Dials: " + (DialView.solidDials ? "SOLID" : "GLASS"));
        });
        root.addView(solidBtn);
        root.addView(button("Stop everything", v -> stopAll()));

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(14f);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        log("Package: " + getPackageName());
        log("Overlay permission granted: " + Settings.canDrawOverlays(this));
        log("If not granted run: adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    }

    private Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setOnClickListener(l);
        return btn;
    }

    private void log(String s) {
        Log.i(TAG, s);
        logView.append(s + "\n");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int targetId() {
        try { return Integer.parseInt(idField.getText().toString().trim()); }
        catch (Exception e) { return 2; }
    }

    private Display targetDisplay() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(targetId());
        if (d == null) log("Display " + targetId() + " not found. Use List displays.");
        return d;
    }

    private void listDisplays() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays()) {
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            log("id=" + d.getDisplayId() + " name=" + d.getName() + " " + m.widthPixels + "x" + m.heightPixels
                    + " dpi=" + m.densityDpi + " flags=0x" + Integer.toHexString(d.getFlags())
                    + " state=" + d.getState() + " rot=" + d.getRotation());
        }
        log("(flag bits: 1 supportsProtectedBuffers, 2 secure, 4 private, 8 presentation, 16 round)");
    }

    private void showOverlay() {
        Display d = targetDisplay();
        if (d == null) return;
        if (!Settings.canDrawOverlays(this)) { log("Overlay permission missing, see command above."); return; }
        try {
            removeOverlay();
            Context dc = createDisplayContext(d);
            overlayWm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
            overlayView = new DialView(dc);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            overlayWm.addView(overlayView, lp);
            log("Method A: overlay added without error. Look at the cluster.");
        } catch (Throwable t) {
            log("Method A FAILED: " + t);
        }
    }

    private void showMapFirst() {
        Display d = targetDisplay();
        if (d == null) return;
        if (d.getDisplayId() != 2) { log("Map-first preview only enabled for cluster display 2."); return; }
        if (!Settings.canDrawOverlays(this)) { log("Overlay permission missing."); return; }
        try {
            stopAll();
            Context dc = createDisplayContext(d);
            overlayWm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
            overlayView = new MapFirstView(dc);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            overlayWm.addView(overlayView, lp);
            log("MAP-FIRST preview on display 2. PARKED ONLY. Press Stop everything to restore stock cluster.");
        } catch (Throwable t) { log("MAP-FIRST FAILED: " + t); removeOverlay(); }
    }

    private void showPresentation() {
        Display d = targetDisplay();
        if (d == null) return;
        try {
            if (presentation != null) presentation.dismiss();
            presentation = new Presentation(this, d);
            presentation.setContentView(new DialView(presentation.getContext()));
            presentation.show();
            log("Method B: presentation shown without error. Look at the cluster.");
        } catch (Throwable t) {
            log("Method B FAILED: " + t);
        }
    }

    private void launchActivity() {
        try {
            Intent i = new Intent(this, ClusterActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(targetId());
            startActivity(i, o.toBundle());
            log("Method C: launch requested for display " + targetId() + ". Look at the cluster.");
        } catch (Throwable t) {
            log("Method C FAILED: " + t);
        }
    }

    private void removeOverlay() {
        try { if (overlayWm != null && overlayView != null) overlayWm.removeView(overlayView); } catch (Throwable ignored) {}
        overlayWm = null; overlayView = null;
    }

    private void stopAll() {
        removeOverlay();
        try { if (presentation != null) presentation.dismiss(); } catch (Throwable ignored) {}
        presentation = null;
        if (ClusterActivity.instance != null) ClusterActivity.instance.finish();
        log("Stopped.");
    }

    @Override protected void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
