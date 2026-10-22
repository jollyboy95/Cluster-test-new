package com.clusterprobe;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
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

import rikka.shizuku.Shizuku;

/**
 * Control panel. Runs on display 0 (the main head-unit screen), and drives ClusterActivity on
 * display 2 (the physical cluster). Everything here uses public Android APIs only - nothing
 * tries to touch the signature-protected GWM adapter / theme_config interface.
 */
public class MainActivity extends android.app.Activity {

    private static final int REQ_CAPTURE = 4201;
    private static final int REQ_GWM_GET_DATA = 4202;
    private static final String GWM_GET_DATA_PERMISSION = "com.gwm.android.adapter.permission.GET_DATA";
    private static final int CLUSTER_DISPLAY_ID = 2;
    private static final int HUD_DISPLAY_ID = 3; // per earlier "List displays" findings - verify on your unit

    private TextView log;
    private TextView themeLabel;
    private ImageView preview;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean previewLoopRunning = false;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermListener =
            (requestCode, grantResult) -> main.post(() -> {
                boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                appendLog("Shizuku permission " + (granted ? "granted" : "denied") + ".");
                if (granted) {
                    appendLog("Retrying GWM logcat data start now that permission is granted...");
                    GwmLogcatVehicleDataSource.start(msg -> appendLog(msg));
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLogger.install(this);
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
            // Shizuku binder not alive yet - fine, the button's own check handles that case too.
        }

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

        Button toggleReadout = addButton(root, "Big centre number: OFF (stock readout already shows real speed)", null);
        toggleReadout.setOnClickListener(v -> {
            DialView.showCenterReadout = !DialView.showCenterReadout;
            toggleReadout.setText("Big centre number: " + (DialView.showCenterReadout ? "ON" : "OFF"));
            DialView.invalidateAll();
            appendLog("Big centre readout " + (DialView.showCenterReadout ? "shown" : "hidden") + " on Apex themes.");
        });

        addButton(root, "Launch on cluster (display " + CLUSTER_DISPLAY_ID + ") - Activity", () -> launchOnDisplay(CLUSTER_DISPLAY_ID));
        addButton(root, "Launch on HUD (display " + HUD_DISPLAY_ID + ") - Activity, experimental", () -> launchOnDisplay(HUD_DISPLAY_ID));
        addButton(root, "Stop cluster window", this::stopCluster);

        addSection(root, "Overlay window (sits above the stock nav layer)");
        addButton(root, "Overlay on cluster (display " + CLUSTER_DISPLAY_ID + ")", () -> showOverlay(CLUSTER_DISPLAY_ID));
        addButton(root, "Overlay on HUD (display " + HUD_DISPLAY_ID + ") - experimental", () -> showOverlay(HUD_DISPLAY_ID));
        addButton(root, "Stop all overlays", () -> {
            OverlayManager.hideAll();
            appendLog("All overlay windows removed.");
        });

        addSection(root, "Live vehicle data (android.car, standard API)");
        addButton(root, "Start live speed/RPM", () -> {
            appendLog("Starting VehicleDataSource (android.car.Car)...");
            VehicleDataSource.start(getApplicationContext(), msg -> appendLog(msg));
        });
        addButton(root, "Stop live data (back to demo animation)", () -> {
            VehicleDataSource.stop();
            appendLog("Live data stopped - demo animation resumes on next relaunch of a dial window.");
        });

        addSection(root, "Live vehicle data (GWM logcat scrape via Shizuku - car.basic.*)");
        addButton(root, "Start GWM logcat data", () -> {
            appendLog("Starting GwmLogcatVehicleDataSource (Shizuku UserService -> logcat)...");
            GwmLogcatVehicleDataSource.start(msg -> appendLog(msg));
        });
        addButton(root, "Stop GWM logcat data", () -> {
            GwmLogcatVehicleDataSource.stop();
            appendLog("GWM logcat data source stopped.");
        });

        addSection(root, "GWM adapter permission probe (com.tank.priborka / h9cluster both use this)");
        addButton(root, "Check GET_DATA permission", this::probeGwmGetDataPermission);

        addSection(root, "Experimental: capture display content");
        addButton(root, "Test map capture (MediaProjection)", this::testCapture);
        Button toggleCapture = addButton(root, "Use captured frame in cluster: OFF", null);
        toggleCapture.setOnClickListener(v -> {
            DialView.useCaptureOverlay = !DialView.useCaptureOverlay;
            toggleCapture.setText("Use captured frame in cluster: " + (DialView.useCaptureOverlay ? "ON" : "OFF"));
            DialView.invalidateAll();
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
        ClusterActivity.applyThemeChangeToAll();
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

    private void launchOnDisplay(int displayId) {
        try {
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            Intent i = new Intent(this, ClusterActivity.class);
            i.putExtra(ClusterActivity.EXTRA_THEME_ORDINAL, DialView.theme.ordinal());
            i.putExtra(ClusterActivity.EXTRA_USE_CAPTURE, DialView.useCaptureOverlay);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(i, opts.toBundle());
            appendLog("Launch requested on display " + displayId + " via ActivityOptions.setLaunchDisplayId.");
        } catch (SecurityException se) {
            appendLog("Denied on display " + displayId + ": " + se.getMessage()
                    + "  (this app likely lacks the permission a normal app needs to target a non-default "
                    + "display from in-process; \"am start --display " + displayId + "\" from adb shell "
                    + "worked earlier because the shell UID has more latitude than a regular app).");
        } catch (Exception e) {
            appendLog("Launch on display " + displayId + " failed: " + e);
        }
    }

    private void showOverlay(int displayId) {
        appendLog("Requesting overlay window on display " + displayId + " (TYPE_APPLICATION_OVERLAY)...");
        OverlayManager.show(getApplicationContext(), displayId, new OverlayManager.Result() {
            @Override public void ok() {
                main.post(() -> appendLog("Overlay added on display " + displayId
                        + ". Check whether it's now ABOVE the stock nav graphics."));
            }
            @Override public void fail(String reason) {
                main.post(() -> appendLog("Overlay on display " + displayId + " failed: " + reason));
            }
        });
    }

    private void stopCluster() {
        if (ClusterActivity.hasAnyOpen()) {
            ClusterActivity.stopAll();
            appendLog("Closed all open cluster/HUD windows.");
        } else {
            appendLog("No cluster window is currently open (from this app's point of view).");
        }
    }

    /**
     * Read-only diagnostic: does this GWM build actually let a normal app hold
     * com.gwm.android.adapter.permission.GET_DATA? Two independent third-party apps
     * (com.tank.priborka, net.adminrunet.h9cluster) use it as their primary vehicle-data path
     * (not a fallback like SmartSplit treats it), which only makes sense if it's grantable to a
     * normal app - but SmartSplit's own "gwm_adapter unavailable or permission denied" string
     * shows it can also fail, so this checks for real rather than assuming either way. Never
     * touches car.ipk_setting.* or any write/theme-coding interface - GET_DATA is a read
     * permission on a different, unrelated GWM service.
     */
    private void probeGwmGetDataPermission() {
        PackageManager pm = getPackageManager();
        try {
            PermissionInfo info = pm.getPermissionInfo(GWM_GET_DATA_PERMISSION, 0);
            appendLog("GET_DATA exists on this device. protectionLevel=0x"
                    + Integer.toHexString(info.protectionLevel) + " (" + decodeProtectionLevel(info.protectionLevel) + ")"
                    + (info.packageName != null ? "  declared by " + info.packageName : ""));
        } catch (PackageManager.NameNotFoundException e) {
            appendLog("GET_DATA permission isn't registered on this device at all - the service "
                    + "that declares it (GwmAdapterServer or similar) may not be installed/active here.");
            return;
        } catch (Throwable t) {
            appendLog("Couldn't read GET_DATA's PermissionInfo: " + t);
        }

        int checkResult = checkSelfPermission(GWM_GET_DATA_PERMISSION);
        if (checkResult == PackageManager.PERMISSION_GRANTED) {
            appendLog("GET_DATA is already GRANTED to ClusterProbe.");
            return;
        }
        appendLog("GET_DATA not currently granted - requesting it now (watch for a system prompt, "
                + "or it may resolve silently if it's a normal/signature-mismatched permission).");
        try {
            requestPermissions(new String[]{GWM_GET_DATA_PERMISSION}, REQ_GWM_GET_DATA);
        } catch (Throwable t) {
            appendLog("requestPermissions() threw: " + t);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_GWM_GET_DATA) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        appendLog("GET_DATA permission request result: " + (granted ? "GRANTED" : "DENIED")
                + (granted ? " - real FDBus-based vehicle data access may be viable here, worth "
                    + "building a proper data source for it." : " - this GWM build is blocking it for "
                    + "third-party apps, matching com.tank.priborka not working on this car either."));
    }

    /** Manual decode instead of PermissionInfo.protectionToString() - that's a real framework
     *  method that must exist on-DEVICE at runtime (this head unit is Android 9 / API 28, and
     *  the method's exact API-level floor isn't confirmed), whereas these are plain int
     *  constants that get compiled in as literals - zero risk of a NoSuchMethodError on-device
     *  either way. Base encoding (0=normal,1=dangerous,2=signature,3=signatureOrSystem) has been
     *  stable since API 1. */
    private static String decodeProtectionLevel(int level) {
        int base = level & 0xf;
        String baseStr;
        switch (base) {
            case 0: baseStr = "normal"; break;
            case 1: baseStr = "dangerous"; break;
            case 2: baseStr = "signature"; break;
            case 3: baseStr = "signatureOrSystem"; break;
            default: baseStr = "unknown base " + base; break;
        }
        int flags = level & ~0xf;
        return flags == 0 ? baseStr : baseStr + " + extra flags 0x" + Integer.toHexString(flags);
    }

    private void testCapture() {
        appendLog("Requesting MediaProjection capture - accept the system prompt if one appears.");
        String err;
        try {
            err = CaptureHelper.requestCapture(this, REQ_CAPTURE);
        } catch (Throwable t) {
            err = "Unexpected: " + t;
        }
        if (err != null) {
            appendLog("Could not request capture: " + err);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            appendLog("Capture not granted (resultCode=" + resultCode + "). Nothing started.");
            return;
        }
        try {
            CaptureHelper.start(this, resultCode, data, new CaptureHelper.FrameListener() {
                @Override public void onFrame(Bitmap bmp) {
                    main.post(() -> {
                        try {
                            preview.setImageBitmap(bmp);
                            float luma = CaptureHelper.averageLuma(bmp);
                            if (luma >= 0 && luma < 4f) {
                                appendLogOnce("Captured frame is nearly all black (avg luma " + luma
                                        + ") - this usually means the content is protected/blanked for capture, "
                                        + "or we're only mirroring an idle screen. Worth checking the preview above.");
                            }
                        } catch (Throwable t) {
                            appendLog("Frame display error: " + t);
                        }
                    });
                }
                @Override public void onLog(String msg) { main.post(() -> appendLog(msg)); }
            });
            startPreviewLoopLog();
        } catch (Throwable t) {
            appendLog("Capture failed to start: " + t + "  (see crash.log for the full trace).");
        }
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
        OverlayManager.hideAll();
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermListener);
        } catch (Throwable ignored) {
        }
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
