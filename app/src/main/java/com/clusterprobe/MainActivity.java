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
import android.widget.EditText;
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
    private static final int REQ_WIRELESS_LOCATION = 4203;
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

        addSection(root, "AAOS cluster-renderer probe (read-only - see docs/H7_DISPLAY_ARCHITECTURE.md)");
        addButton(root, "Probe NavigationRenderer / cluster-renderer service", () -> {
            appendLog("Running NavigationRendererProbe (CarNavigationStatusManager.getInstrumentClusterInfo() "
                    + "+ a bindService() permission check against GWM's own renderer service)...");
            NavigationRendererProbe.run(getApplicationContext(), msg -> appendLog(msg));
            // v0.24: also start listening for com.projection.action.CLUSTER_STATE - a
            // <protected-broadcast> found declared in the real factory com.gwm.app.aap
            // AndroidAutoApp (AndroidAuto.apk/.odex/.vdex pulled off this car and reverse-read,
            // read-only, same methodology as every other static-analysis step in this project -
            // see docs/H7_DISPLAY_ARCHITECTURE.md). Unlike everything else probed so far,
            // RECEIVING a protected-broadcast needs no special permission - only SENDING one
            // does. Folded into this same button/Stop pair rather than a new one, per "limit
            // reinstalls". Keeps listening in the background after the rest of this probe's
            // synchronous checks finish, since the broadcast (if it's sent system-wide at all)
            // likely only fires on an actual mode change, not on a fixed schedule.
            ClusterStateBroadcastListener.start(getApplicationContext(), msg -> appendLog(msg));
        });
        addButton(root, "Stop cluster-renderer probe", () -> {
            NavigationRendererProbe.stop();
            ClusterStateBroadcastListener.stop();
            appendLog("Cluster-renderer probe stopped, any bind torn down, cluster-state broadcast listener unregistered.");
        });

        addSection(root, "Button event probe (experimental - lead from a reference app, see docs)");
        addLabel(root, "Polls the system property \"sys.other.keyevent_notify\" every 300ms, which "
                + "a real commercial app (SmartSplit, built for Haval H9) reads to learn about "
                + "physical button presses. Not yet confirmed on this H7 - this only logs the raw "
                + "value and any change, read-only, no input injection. Press a steering-wheel or "
                + "cluster button on the car while this is running and watch the log.");
        addButton(root, "Run button event probe", () -> {
            appendLog("Starting ButtonEventProbe...");
            ButtonEventProbe.start(msg -> appendLog(msg));
        });
        addButton(root, "Stop button event probe", () -> {
            ButtonEventProbe.stop();
            appendLog("Button event probe stopped.");
        });

        addSection(root, "Android Auto receiver (experimental, OFF by default - see docs)");
        addLabel(root, "Builds ClusterProbe's own Android Auto receiver, independent of GWM's "
                + "factory one. USB/AOAP negotiation only, as far as ConnectionStage.AOAP_START_"
                + "ACCESSORY - nothing past that is implemented yet. Does nothing at all unless "
                + "the toggle below is ON and the button is pressed while a phone is plugged in.");
        Button aaReceiverToggle = addButton(root, "AA receiver master toggle: OFF", null);
        aaReceiverToggle.setOnClickListener(v -> {
            com.clusterprobe.aareceiver.AndroidAutoReceiver.enabled =
                    !com.clusterprobe.aareceiver.AndroidAutoReceiver.enabled;
            boolean on = com.clusterprobe.aareceiver.AndroidAutoReceiver.enabled;
            aaReceiverToggle.setText("AA receiver master toggle: " + (on ? "ON" : "OFF"));
            appendLog("AA receiver master toggle: " + (on ? "ON - the negotiation button will now "
                    + "actually do something." : "OFF (default) - the negotiation button will log "
                    + "that it's disabled and do nothing else."));
        });
        addButton(root, "Run USB AOAP negotiation (experimental)", () -> {
            appendLog("Running AndroidAutoReceiver USB/AOAP negotiation...");
            com.clusterprobe.aareceiver.AndroidAutoReceiver.run(getApplicationContext(),
                    new com.clusterprobe.aareceiver.AndroidAutoReceiver.StatusListener() {
                        @Override public void onStatus(String msg) { appendLog(msg); }
                        @Override public void onStageUpdate(com.clusterprobe.aareceiver.ConnectionStage stage,
                                com.clusterprobe.aareceiver.AndroidAutoReceiver.StageStatus status) {
                            appendLog("  stage: " + stage + " -> " + status);
                        }
                    });
        });
        addButton(root, "Show all connection-stage statuses", () -> {
            appendLog("--- Android Auto receiver: connection stage statuses ---");
            for (com.clusterprobe.aareceiver.ConnectionStage s : com.clusterprobe.aareceiver.ConnectionStage.values()) {
                com.clusterprobe.aareceiver.AndroidAutoReceiver.StageStatus st =
                        com.clusterprobe.aareceiver.AndroidAutoReceiver.getStageStatuses().get(s);
                appendLog(s + " [" + st + "]: " + s.description);
            }
        });
        addButton(root, "Stop AA receiver negotiation", () -> {
            com.clusterprobe.aareceiver.AndroidAutoReceiver.stop();
            appendLog("AA receiver negotiation stopped (if it was running).");
        });

        addSection(root, "Wireless AA bootstrap (experimental, same OFF-by-default toggle above)");
        addLabel(root, "Separate transport for the same receiver: hosts our own Wi-Fi AP, "
                + "advertises the real Android-Auto-Wireless Bluetooth RFCOMM service "
                + "(4de17a00-52cb-11e6-bdf4-0800200c9a66), and runs the WifiStartRequest/"
                + "WifiInfoResponse credential exchange - reimplemented from aa-proxy-rs's actual "
                + "working head-unit code, see docs. Stops once the phone's TCP session connection "
                + "is confirmed - not yet wired into the VersionRequest exchange. Phone must "
                + "already be Bluetooth-paired with this head unit the normal way first. Needs the "
                + "AA receiver master toggle above ON, plus location permission (required by "
                + "Android for hosting a local hotspot).");
        addButton(root, "Run wireless AA bootstrap (experimental)", () -> {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                appendLog("ACCESS_FINE_LOCATION not granted yet - requesting it (required for "
                        + "WifiManager.startLocalOnlyHotspot()). Press the button again once granted.");
                try {
                    requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                            REQ_WIRELESS_LOCATION);
                } catch (Throwable t) {
                    appendLog("requestPermissions() threw: " + t);
                }
                return;
            }
            appendLog("Running wireless AA bootstrap...");
            com.clusterprobe.aareceiver.AaWirelessBootstrapServer.run(getApplicationContext(),
                    new com.clusterprobe.aareceiver.AndroidAutoReceiver.StatusListener() {
                        @Override public void onStatus(String msg) { appendLog(msg); }
                        @Override public void onStageUpdate(com.clusterprobe.aareceiver.ConnectionStage stage,
                                com.clusterprobe.aareceiver.AndroidAutoReceiver.StageStatus status) {
                            appendLog("  stage: " + stage + " -> " + status);
                        }
                    });
        });
        addButton(root, "Stop wireless AA bootstrap", () -> {
            com.clusterprobe.aareceiver.AaWirelessBootstrapServer.stop();
            appendLog("Wireless AA bootstrap stopped (if it was running).");
        });

        addSection(root, "Wireless AA manual connect (experimental - OpenAuto-style, simpler)");
        addLabel(root, "Found by inspecting the real OpenAuto source: its own wireless mode is "
                + "just a plain TCP connection to the phone's IP on port 5277 - no Bluetooth, no "
                + "hotspot hosting on our side at all. On the phone: open Android Auto's own "
                + "Settings, tap the version number repeatedly to unlock developer settings, then "
                + "enable \"Start head unit server\" - it will show an IP address. Both this head "
                + "unit and the phone need to already be on the same Wi-Fi network first (e.g. "
                + "both on the same home/mobile hotspot). Type that IP below.");
        EditText wirelessIpField = addEditText(root, "Phone IP address (e.g. 192.168.1.42)");
        Button toggleClusterLaunch = addButton(root, "Show session on: DEFAULT DISPLAY", null);
        toggleClusterLaunch.setOnClickListener(v -> {
            com.clusterprobe.aareceiver.AaWirelessManualConnect.launchOnClusterDisplay =
                    !com.clusterprobe.aareceiver.AaWirelessManualConnect.launchOnClusterDisplay;
            boolean onCluster = com.clusterprobe.aareceiver.AaWirelessManualConnect.launchOnClusterDisplay;
            toggleClusterLaunch.setText("Show session on: " + (onCluster
                    ? "CLUSTER (display " + CLUSTER_DISPLAY_ID + ", via Shizuku)" : "DEFAULT DISPLAY"));
            appendLog("Next manual-IP connect will open the session screen on "
                    + (onCluster ? "the cluster display (needs Shizuku permission granted)" : "the default display") + ".");
        });
        addButton(root, "Connect (manual IP)", () -> {
            String ip = wirelessIpField.getText().toString();
            appendLog("Connecting to phone via manual IP...");
            com.clusterprobe.aareceiver.AaWirelessManualConnect.connect(getApplicationContext(), ip,
                    com.clusterprobe.aareceiver.AaWirelessManualConnect.DEFAULT_PORT,
                    new com.clusterprobe.aareceiver.AndroidAutoReceiver.StatusListener() {
                        @Override public void onStatus(String msg) { appendLog(msg); }
                        @Override public void onStageUpdate(com.clusterprobe.aareceiver.ConnectionStage stage,
                                com.clusterprobe.aareceiver.AndroidAutoReceiver.StageStatus status) {
                            appendLog("  stage: " + stage + " -> " + status);
                        }
                    });
        });
        addButton(root, "Stop manual connect", () -> {
            com.clusterprobe.aareceiver.AaWirelessManualConnect.stop();
            appendLog("Wireless manual connect stopped (if it was running).");
        });

        addSection(root, "Wireless AA: Wi-Fi Direct feasibility probe (experimental, research only)");
        addLabel(root, "Found by inspecting headunit-borconi (\"Headunit Reloaded\") and open-headunit "
                + "- both real Android-native AA receivers. Wi-Fi Direct (WifiP2pManager) is a "
                + "different Android API from the hotspot one that failed last time - worth a cheap "
                + "test to see if THIS hardware allows it. Only tests whether createGroup() itself "
                + "works - does not yet try to get a real phone to join.");
        addButton(root, "Run Wi-Fi Direct probe", () -> {
            appendLog("Running Wi-Fi Direct feasibility probe...");
            com.clusterprobe.aareceiver.AaWirelessDirectProbe.run(getApplicationContext(),
                    new com.clusterprobe.aareceiver.AndroidAutoReceiver.StatusListener() {
                        @Override public void onStatus(String msg) { appendLog(msg); }
                        @Override public void onStageUpdate(com.clusterprobe.aareceiver.ConnectionStage stage,
                                com.clusterprobe.aareceiver.AndroidAutoReceiver.StageStatus status) {
                            appendLog("  stage: " + stage + " -> " + status);
                        }
                    });
        });
        addButton(root, "Stop Wi-Fi Direct probe", () -> {
            com.clusterprobe.aareceiver.AaWirelessDirectProbe.stop();
            appendLog("Wi-Fi Direct probe stopped (if it was running).");
        });

        addSection(root, "Known-working third-party AA receiver on cluster/HUD (Shizuku \"am start --display\")");
        addLabel(root, "Headunit Reloaded is confirmed working on this car by direct testing. This "
                + "launches its own already-installed app straight onto display " + CLUSTER_DISPLAY_ID
                + " or " + HUD_DISPLAY_ID + " via the same shell-level \"am start --display\" route "
                + "that already works for our own ClusterActivity - no code of theirs is used, this "
                + "just opens their app the way tapping its icon would, aimed at a different screen. "
                + "Runs in parallel with our own receiver above, not instead of it.");
        addButton(root, "Launch Headunit Reloaded on cluster (display " + CLUSTER_DISPLAY_ID + ")", () -> {
            if (!ThirdPartyAaLauncher.isInstalled(getApplicationContext(), ThirdPartyAaLauncher.HEADUNIT_RELOADED_PACKAGE)) {
                appendLog("Headunit Reloaded (" + ThirdPartyAaLauncher.HEADUNIT_RELOADED_PACKAGE
                        + ") is not installed on this unit.");
                return;
            }
            ThirdPartyAaLauncher.launchOnDisplay(CLUSTER_DISPLAY_ID, ThirdPartyAaLauncher.HEADUNIT_RELOADED_PACKAGE,
                    ThirdPartyAaLauncher.HEADUNIT_RELOADED_LAUNCH_ACTIVITY, this::appendLog);
        });
        addButton(root, "Launch Headunit Reloaded on HUD (display " + HUD_DISPLAY_ID + ") - experimental", () -> {
            if (!ThirdPartyAaLauncher.isInstalled(getApplicationContext(), ThirdPartyAaLauncher.HEADUNIT_RELOADED_PACKAGE)) {
                appendLog("Headunit Reloaded (" + ThirdPartyAaLauncher.HEADUNIT_RELOADED_PACKAGE
                        + ") is not installed on this unit.");
                return;
            }
            ThirdPartyAaLauncher.launchOnDisplay(HUD_DISPLAY_ID, ThirdPartyAaLauncher.HEADUNIT_RELOADED_PACKAGE,
                    ThirdPartyAaLauncher.HEADUNIT_RELOADED_LAUNCH_ACTIVITY, this::appendLog);
        });
        addLabel(root, "DiAuto has a real cluster feature on BYD cars, but it's a BYD-specific "
                + "broadcast this car almost certainly has no receiver for - not installed/confirmed "
                + "on this car yet either (get a release APK from its GitHub releases page first). "
                + "These buttons target its own video Activity directly, same generic display-forcing "
                + "trick, no BYD dependency - this is the \"push the whole map to display 2\" test.");
        addButton(root, "Launch DiAuto on cluster (display " + CLUSTER_DISPLAY_ID + ")", () -> {
            if (!ThirdPartyAaLauncher.isInstalled(getApplicationContext(), ThirdPartyAaLauncher.DIAUTO_PACKAGE)) {
                appendLog("DiAuto (" + ThirdPartyAaLauncher.DIAUTO_PACKAGE + ") is not installed on this unit.");
                return;
            }
            ThirdPartyAaLauncher.launchOnDisplay(CLUSTER_DISPLAY_ID, ThirdPartyAaLauncher.DIAUTO_PACKAGE,
                    ThirdPartyAaLauncher.DIAUTO_LAUNCH_ACTIVITY, this::appendLog);
        });
        addButton(root, "Launch DiAuto on HUD (display " + HUD_DISPLAY_ID + ") - experimental", () -> {
            if (!ThirdPartyAaLauncher.isInstalled(getApplicationContext(), ThirdPartyAaLauncher.DIAUTO_PACKAGE)) {
                appendLog("DiAuto (" + ThirdPartyAaLauncher.DIAUTO_PACKAGE + ") is not installed on this unit.");
                return;
            }
            ThirdPartyAaLauncher.launchOnDisplay(HUD_DISPLAY_ID, ThirdPartyAaLauncher.DIAUTO_PACKAGE,
                    ThirdPartyAaLauncher.DIAUTO_LAUNCH_ACTIVITY, this::appendLog);
        });
        addButton(root, "Request Shizuku permission (needed for the above)", () -> {
            try {
                Shizuku.requestPermission(4204);
                appendLog("Shizuku permission requested - respond to the prompt, then retry the launch button.");
            } catch (Throwable t) {
                appendLog("Shizuku.requestPermission() threw: " + t
                        + " - is the Shizuku app/service running on this unit?");
            }
        });

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
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_GWM_GET_DATA) {
            appendLog("GET_DATA permission request result: " + (granted ? "GRANTED" : "DENIED")
                    + (granted ? " - real FDBus-based vehicle data access may be viable here, worth "
                        + "building a proper data source for it." : " - this GWM build is blocking it for "
                        + "third-party apps, matching com.tank.priborka not working on this car either."));
            return;
        }
        if (requestCode == REQ_WIRELESS_LOCATION) {
            appendLog("ACCESS_FINE_LOCATION permission request result: " + (granted ? "GRANTED - "
                    + "press \"Run wireless AA bootstrap\" again to start it." : "DENIED - the "
                    + "wireless bootstrap button will keep asking until this is granted."));
        }
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
        // v0.25: read-only resolver check FIRST, before touching startActivityForResult() at all.
        // An earlier on-car run threw ActivityNotFoundException from that call - this asks
        // PackageManager directly (queryIntentActivities, never launches anything) whether the
        // capture-consent screen is genuinely absent on this build system-wide, or something more
        // specific. See CaptureHelper.checkCaptureIntentResolvable().
        try {
            appendLog(CaptureHelper.checkCaptureIntentResolvable(this));
        } catch (Throwable t) {
            appendLog("MediaProjection resolver check threw: " + t);
        }
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
        NavigationRendererProbe.stop();
        ClusterStateBroadcastListener.stop();
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

    private EditText addEditText(LinearLayout parent, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextColor(Color.parseColor("#f3eee8"));
        et.setHintTextColor(Color.parseColor("#9a8c78"));
        parent.addView(et);
        return et;
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
