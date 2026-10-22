package com.clusterprobe;

import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Runs with shell UID in its own process (Shizuku spawns this via bindUserService - see
 * GwmLogcatVehicleDataSource). Shell UID can read logcat tags belonging to other
 * apps/processes, which a normal app-UID process cannot; this class exists ONLY to read that
 * existing debug log stream and hand lines back to the main app process. It never writes to a
 * device file, system property, or vehicle setting, and it never touches the signature-protected
 * car.ipk_setting.* interface.
 *
 * Must have a public no-arg constructor - Shizuku instantiates it directly by reflection in the
 * spawned process.
 */
public class GwmLogcatUserService extends IGwmLogcatService.Stub {

    // Same tag filter SmartSplit uses - these are the tags GWM's own factory adapter/gauge
    // processes log their live car.basic.*/car.off_road_info.* key=value dumps to.
    private static final String[] LOGCAT_CMD = {
            "logcat", "-v", "raw",
            "GA-server:I", "GA-client:I", "GA-ServiceImpl:I", "MyDataAdapter:I", "Information:I",
            "GnssHAL_GnssImpl:V", "GwmPUI_GdcOVSAdapterClient:V", "BuxDataSource:V",
            "SmartNaviActor:I", "GwmNaviActor:I", "CrossCountryManager:I", "*:S"
    };

    private Thread readerThread;
    private Process logcatProcess;
    private volatile boolean stopping = false;

    public GwmLogcatUserService() {
    }

    @Override
    public synchronized void start(IGwmLogcatCallback callback) {
        if (readerThread != null) return; // already running
        stopping = false;
        readerThread = new Thread(() -> runLoop(callback), "gwm-logcat-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void runLoop(IGwmLogcatCallback callback) {
        try {
            // Clear first so we don't replay a huge stale backlog.
            new ProcessBuilder("logcat", "-c").start().waitFor();

            ProcessBuilder pb = new ProcessBuilder(LOGCAT_CMD);
            pb.redirectErrorStream(true);
            logcatProcess = pb.start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()))) {
                String line;
                while (!stopping && (line = r.readLine()) != null) {
                    try {
                        callback.onLine(line);
                    } catch (RemoteException dead) {
                        // App process is gone or callback died - stop quietly.
                        break;
                    }
                }
            }
            safeStopReason(callback, stopping ? "stopped" : "logcat stream ended");
        } catch (IOException | InterruptedException e) {
            safeStopReason(callback, "logcat exec failed: " + e);
        } finally {
            killProcess();
        }
    }

    private void safeStopReason(IGwmLogcatCallback callback, String reason) {
        try {
            callback.onStopped(reason);
        } catch (RemoteException ignored) {
        }
    }

    private void killProcess() {
        if (logcatProcess != null) {
            try {
                logcatProcess.destroy();
            } catch (Exception ignored) {
            }
            logcatProcess = null;
        }
    }

    @Override
    public synchronized void stop() {
        stopping = true;
        killProcess();
        readerThread = null;
    }

    @Override
    public void destroy() {
        stop();
        System.exit(0);
    }
}
