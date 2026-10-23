package com.clusterprobe;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * One-shot, read-only logcat snapshot via the same Shizuku shell-UID UserService mechanism
 * GwmLogcatVehicleDataSource already uses for live vehicle data - but a completely separate bind
 * (its own processNameSuffix) so it never interferes with that stream if it happens to be
 * running at the same time.
 *
 * Purpose: NavigationRendererProbe's Car-connect step can time out waiting for
 * onServiceConnected() with no exception at all - android.car.Car's connection to car_service
 * doesn't go through the same exception-on-denial path as a normal bindService() call, so a
 * permission/SELinux-level rejection on this build may simply never reach the app as a
 * Throwable. This grabs the recent system log right after that happens and reports only the
 * lines that look car-service/permission-denial-related, which is the only way left to find out
 * *why* the connection never completes.
 *
 * Must be called from a background thread - it blocks for up to ~6s waiting on the dump.
 * Nothing here writes to any device, file, property or vehicle setting.
 */
public class CarServiceLogSnapshot {

    public static final class Result {
        public final List<String> matchingLines; // never null
        public final int totalLinesScanned;
        public final String note; // non-null when something's worth telling the user about (skip reason, timeout, etc.)

        Result(List<String> matchingLines, int totalLinesScanned, String note) {
            this.matchingLines = matchingLines;
            this.totalLinesScanned = totalLinesScanned;
            this.note = note;
        }
    }

    private static final String[] KEYWORDS = {
            "car_service", "icar", "carservice", "carservicehelper", "denied",
            "securityexception", "illegalstateexception", "avc:", "not allowed",
            "permission denial", "binder",
            // v0.25: com.gwm.app.aap (the factory Android Auto receiver)-specific tokens, added so
            // a snapshot taken WHILE Android Auto is actively projecting navigation might surface
            // which cluster profile/CLUSTER_TYPE it actually loaded on this car - every one of the
            // 67 shipped .properties profiles found in AndroidAuto.apk showed a 600x350 inset
            // regardless of CLUSTER_TYPE, but mask_cluster_map.png is 1920x620, so something can
            // render bigger than any sampled profile - this is read-only logcat scraping, the same
            // mechanism as every other keyword above, just a different set of substrings to match.
            // No prior on-car run captured this because AA wasn't confirmed actively projecting at
            // the time - worth one more run specifically while nav guidance is live on-screen.
            "com.gwm.app.aap", "cluster_type", "map_only", "map_tbt", "tbt_only",
            "instrumentcluster", "navigationstatusservice", "cluster_display"
    };

    private static final int MAX_MATCHES_RETURNED = 20;

    public static Result capture() {
        if (!GwmLogcatVehicleDataSource.isShizukuAvailable()) {
            return new Result(new ArrayList<>(), 0,
                    "Shizuku isn't running/reachable - skipping car-service logcat snapshot.");
        }
        if (!GwmLogcatVehicleDataSource.hasPermission()) {
            return new Result(new ArrayList<>(), 0,
                    "Shizuku permission not granted - skipping car-service logcat snapshot "
                            + "(grant it, then re-run this probe, to see why the Car connection times out).");
        }

        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName("com.clusterprobe", GwmLogcatUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("gwm_logcat_dump")
                .debuggable(false)
                .version(1);

        CountDownLatch latch = new CountDownLatch(1);
        List<String> matched = new CopyOnWriteArrayList<>();
        int[] total = {0};
        IGwmLogcatService[] svcHolder = new IGwmLogcatService[1];

        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                IGwmLogcatService svc = IGwmLogcatService.Stub.asInterface(binder);
                svcHolder[0] = svc;
                try {
                    svc.dumpOnce(new IGwmLogcatCallback.Stub() {
                        @Override
                        public void onLine(String line) {
                            total[0]++;
                            String lower = line.toLowerCase();
                            for (String kw : KEYWORDS) {
                                if (lower.contains(kw)) {
                                    matched.add(line);
                                    break;
                                }
                            }
                        }

                        @Override
                        public void onStopped(String reason) {
                            latch.countDown();
                        }
                    });
                } catch (RemoteException e) {
                    latch.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                latch.countDown();
            }
        };

        try {
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            return new Result(new ArrayList<>(), 0, "Shizuku bindUserService failed: " + t);
        }

        boolean done;
        try {
            done = latch.await(6, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            done = false;
        }

        try {
            if (svcHolder[0] != null) svcHolder[0].destroy();
        } catch (RemoteException ignored) {
        }
        try {
            Shizuku.unbindUserService(args, conn, true);
        } catch (Throwable ignored) {
        }

        List<String> capped = matched.size() > MAX_MATCHES_RETURNED
                ? new ArrayList<>(matched.subList(matched.size() - MAX_MATCHES_RETURNED, matched.size()))
                : new ArrayList<>(matched);
        return new Result(capped, total[0], done ? null : "dump timed out after 6s - partial results shown.");
    }
}
