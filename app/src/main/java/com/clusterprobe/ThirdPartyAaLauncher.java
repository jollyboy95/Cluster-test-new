package com.clusterprobe;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/**
 * Launches an already-installed, already-working third-party Android Auto receiver app directly
 * onto a non-default display (the physical cluster or HUD), via a Shizuku-privileged shell "am
 * start --display <id>" - the same route MainActivity's own launchOnDisplay() already found and
 * logged as the workaround when ActivityOptions.setLaunchDisplayId() is denied for an ordinary
 * app's own in-process call (see that method's catch block): the shell UID has the latitude a
 * regular app does not.
 *
 * Why this class exists at all: this project's own from-scratch Android Auto receiver
 * (com.clusterprobe.aareceiver.*) is real protocol work, built and cross-checked against several
 * independent real implementations, but it has not yet been proven on a phone. Headunit Reloaded
 * (and DiAuto, both inspected as black boxes elsewhere in this project - see
 * docs/H7_DISPLAY_ARCHITECTURE.md) are CONFIRMED working on this exact car, by the user's own
 * testing. Rather than wait on our own receiver before the cluster/HUD display work can start,
 * this launches Headunit Reloaded's own activity straight onto display 2 or 3 - no code of theirs
 * is used or included here, this only runs the already-installed app the same way tapping its icon
 * would, just targeted at a different display. Our own receiver keeps being finished in parallel
 * (see the "Wireless AA" sections above) as the long-term, no-third-party-APK-dependency path.
 *
 * Package/activity names were read from the uploaded, user-confirmed-working
 * "Headunit_Reloaded_vV8.2_.0_Patched.apk" 's own AndroidManifest.xml (package gb.xxy.hr) - not
 * guessed. If the user installs a different build with a different package name, update
 * HEADUNIT_RELOADED_PACKAGE/ACTIVITY below.
 */
public final class ThirdPartyAaLauncher {

    public static final String HEADUNIT_RELOADED_PACKAGE = "gb.xxy.hr";
    /** DispatcherActivity is the one declared with the LAUNCHER intent filter in the APK's
     *  manifest (MainActivity is the screen it dispatches to after its own startup checks) - use
     *  the same entry point tapping the app's icon would use. */
    public static final String HEADUNIT_RELOADED_LAUNCH_ACTIVITY = "gb.xxy.hr.activities.DispatcherActivity";

    /**
     * DiAuto (github.com/shihabal3amri/DiAuto, AGPL-3.0, fork of open-headunit) - read from its
     * OWN source (not a binary guess, unlike the APK-only apps above): applicationId
     * "com.andrerinas.headunitrevived" per its README/build.gradle.kts, and
     * app/src/main/AndroidManifest.xml declares the exported, LAUNCHER-registered ".main.MainActivity"
     * plus a SEPARATE exported ".aap.AapProjectionActivity" that is the one actually showing live
     * AA video. Targeting AapProjectionActivity directly (not MainActivity) for display 2/3, since
     * that is the window that matters for "push the video to that display" - MainActivity is just
     * its settings/home screen.
     *
     * Important caveat, unlike Headunit Reloaded above: the user has NOT confirmed DiAuto
     * installed/working on this car - only Headunit Reloaded and DiPlay were tested directly. DiAuto's
     * own documented instrument-cluster feature is BYD-specific (it sends BYD's own "AutoNavi"
     * broadcast to a BYD-shipped ClusterDebug receiver - see docs/H7_DISPLAY_ARCHITECTURE.md) and
     * almost certainly has no GWM equivalent on this car, so installing it would not automatically
     * put maps on this cluster. Forcing its AapProjectionActivity onto display 2/3 with this class
     * is the generic, OEM-independent mechanism instead - the same trick as Headunit Reloaded,
     * just aimed at DiAuto's window. This project has no Android SDK/NDK available to build DiAuto
     * from its source here; a release APK would need to come from its GitHub Releases page and be
     * sideloaded by the user first, same as Headunit Reloaded was.
     */
    public static final String DIAUTO_PACKAGE = "com.andrerinas.headunitrevived";
    public static final String DIAUTO_LAUNCH_ACTIVITY = "com.andrerinas.headunitrevived.aap.AapProjectionActivity";

    public interface Listener {
        void onStatus(String msg);
    }

    private ThirdPartyAaLauncher() {
    }

    /** MainActivity.appendLog() touches a TextView directly with no internal main-thread
     *  posting, matching this project's existing convention (see AaWirelessManualConnect's own
     *  log() helper) that the CALLER posts to the main thread before invoking a status listener
     *  from a background thread - not the listener's own responsibility. */
    private static void postStatus(Listener listener, String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> listener.onStatus(msg));
    }

    public static boolean isInstalled(android.content.Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Runs "am start --display <displayId> -n <packageName>/<activityName>" as the Shizuku
     *  (shell-equivalent) UID. Requires Shizuku permission to already be granted - callers should
     *  check/request that the same way GwmLogcatVehicleDataSource does, before calling this. */
    public static void launchOnDisplay(int displayId, String packageName, String activityName, Listener listener) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            listener.onStatus("Shizuku permission not granted - request it first (same Shizuku "
                    + "permission the GWM logcat data source uses).");
            return;
        }
        String component = packageName + "/" + activityName;
        String cmd = "am start --display " + displayId + " -n " + component;
        listener.onStatus("Running via Shizuku shell: " + cmd);
        new Thread(() -> runShellCommand(cmd, listener), "third-party-aa-launch").start();
    }

    private static void runShellCommand(String cmd, Listener listener) {
        Process process = null;
        try {
            process = Shizuku.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            StringBuilder outSb = new StringBuilder();
            StringBuilder errSb = new StringBuilder();
            readStream(process.getInputStream(), outSb);
            readStream(process.getErrorStream(), errSb);
            int exit = process.waitFor();
            postStatus(listener, "am start exit=" + exit
                    + (outSb.length() > 0 ? " stdout=" + outSb : "")
                    + (errSb.length() > 0 ? " stderr=" + errSb : ""));
            if (exit == 0) {
                postStatus(listener, "If the app is installed and the display id is right, it should "
                        + "now be showing on that display. If nothing appeared, check \"adb logcat\" "
                        + "or the green/engineering menu for a rejection reason - some automotive "
                        + "builds restrict which displays a non-system app's activity may land on "
                        + "even from the shell UID.");
            }
        } catch (Throwable t) {
            postStatus(listener, "Shizuku shell command failed: " + t);
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void readStream(InputStream in, StringBuilder into) {
        if (in == null) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                into.append(line).append(' ');
            }
        } catch (Exception ignored) {
            // best-effort; a read failure here just means less diagnostic text, not a hard error
        }
    }
}
