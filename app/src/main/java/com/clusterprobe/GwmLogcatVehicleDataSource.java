package com.clusterprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * Reads live vehicle data by tailing the SAME debug logcat stream GWM's own factory adapter
 * process already writes to (tags GA-server/GA-client/GA-ServiceImpl/MyDataAdapter/Information,
 * etc.) and regex-matching the car.basic.* / named property lines out of it - confirmed as the
 * real working mechanism by reverse-reading the SmartSplit APK's strings (see project notes),
 * not guessed. This is read-only: it only ever reads an existing debug log, via a `logcat`
 * child process spawned under shell UID through Shizuku's sanctioned UserService API. It never
 * calls, binds to, or otherwise touches the signature-protected car.ipk_setting.* interface or
 * any factory-coding/write path - that boundary is unrelated to and unaffected by this class.
 *
 * Requires: the user has Shizuku installed, running, and has granted this app the Shizuku
 * permission (see requestPermissionIfNeeded/isAvailable - MainActivity drives that flow).
 */
public class GwmLogcatVehicleDataSource {

    public interface StatusListener { void onStatus(String msg); }

    private static final int USER_SERVICE_REQUEST_CODE = 9100;
    private static GwmLogcatVehicleDataSource instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private StatusListener status;
    private IGwmLogcatService service;
    private final EnumSet<Prop> seen = EnumSet.noneOf(Prop.class);

    private enum Prop { SPEED, RPM, FUEL, RANGE_MAIN_TANK, RANGE_SUB_TANK, RANGE_EV, RANGE_GENERIC, TEMP, GEAR }

    // Named gauge-message patterns (from GaugeAdapter::decodeMsg_read style log lines).
    private static final Pattern SPEED_NAMED = Pattern.compile("GWMVehSpd.*?value\\s+([0-9.]+)");
    private static final Pattern RPM_NAMED = Pattern.compile("fdbEngineSpeed.*?value\\s+([0-9.]+)");

    // Plain car.basic.<key>=<value> dump lines (the periodic full property dump).
    private static final Pattern SPEED_KV = Pattern.compile("car\\.basic\\.(?:vehicle_speed|ivivehiclespeed)=([0-9.]+)");
    private static final Pattern RPM_KV = Pattern.compile("car\\.basic\\.engine_speed=([0-9.]+)");
    private static final Pattern FUEL_KV = Pattern.compile("car\\.basic\\.remain_fuel_percentage=([0-9.]+)");
    // Range: the H7 Vanta is a hybrid, and GWM's own property list (confirmed by reading the
    // Kafo themes app's dex strings - read-only, same as every other third-party APK in this
    // project) has THREE more specific range keys alongside the generic one we were already
    // using: main_tank_remain_odometer (fuel range), sub_tank_remain_odometer (secondary tank,
    // if fitted), and ev_remain_odometer_status (EV/battery range). All four are tried, most
    // specific first, which should also resolve the old "is remain_odometer even a range"
    // caveat - on a hybrid, seeing BOTH a fuel-tank reading and an EV reading come through
    // confirms the key family really does mean remaining range, not something like a service
    // interval, since a service-distance figure wouldn't split by tank/EV like this.
    private static final Pattern RANGE_MAIN_TANK_KV = Pattern.compile("car\\.basic\\.main_tank_remain_odometer=([0-9.]+)");
    private static final Pattern RANGE_SUB_TANK_KV = Pattern.compile("car\\.basic\\.sub_tank_remain_odometer=([0-9.]+)");
    private static final Pattern RANGE_EV_KV = Pattern.compile("car\\.basic\\.ev_remain_odometer_status=([0-9.]+)");
    private static final Pattern RANGE_GENERIC_KV = Pattern.compile("car\\.basic\\.remain_odometer=([0-9.]+)");
    private static final Pattern TEMP_KV = Pattern.compile("car\\.basic\\.outside_temp=(-?[0-9.]+)");
    private static final Pattern GEAR_KV = Pattern.compile("car\\.basic\\.(?:current_gear|gear_status)=(\\S+)");

    public static boolean isShizukuAvailable() {
        try {
            return !Shizuku.isPreV11() && Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Call from an Activity; result arrives via a Shizuku.OnRequestPermissionResultListener
     *  the Activity should register separately (Shizuku delivers it async). */
    public static void requestPermission(Activity unused) {
        try {
            Shizuku.requestPermission(USER_SERVICE_REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    public static void start(StatusListener status) {
        stop();
        instance = new GwmLogcatVehicleDataSource();
        instance.status = status;
        instance.doStart();
    }

    public static void stop() {
        if (instance == null) return;
        instance.doStop();
        instance = null;
    }

    private void log(String msg) {
        if (status != null) main.post(() -> status.onStatus(msg));
    }

    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName(BuildConfigShim.APPLICATION_ID, GwmLogcatUserService.class.getName()))
            .daemon(false)
            .processNameSuffix("gwm_logcat")
            .debuggable(false)
            .version(1);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IGwmLogcatService.Stub.asInterface(binder);
            try {
                service.start(callback);
                log("GWM logcat bridge connected - watching for live car.basic.* data.");
            } catch (RemoteException e) {
                log("Failed to start logcat bridge: " + e);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            log("GWM logcat bridge disconnected.");
        }
    };

    private final IGwmLogcatCallback callback = new IGwmLogcatCallback.Stub() {
        @Override public void onLine(String line) {
            handleLine(line);
        }
        @Override public void onStopped(String reason) {
            log("GWM logcat stream stopped: " + reason);
        }
    };

    private void doStart() {
        if (!isShizukuAvailable()) {
            log("Shizuku isn't running/reachable - start it and keep it authorized first.");
            return;
        }
        if (!hasPermission()) {
            log("Shizuku permission not granted yet - requesting it now (approve the prompt).");
            requestPermission(null);
            return;
        }
        try {
            Shizuku.bindUserService(serviceArgs, connection);
            log("Binding GWM logcat UserService via Shizuku...");
        } catch (Throwable t) {
            log("Shizuku bindUserService failed: " + t);
        }
    }

    private void doStop() {
        try {
            // destroy() (not just stop()) so the shell-UID process actually exits instead of
            // sitting idle - a quick stop/start cycle otherwise risks rebinding to a process
            // that's still mid-teardown. The remote side calls System.exit(0) right after
            // replying, so a RemoteException here from the dying process is expected, not an
            // error worth logging.
            if (service != null) service.destroy();
        } catch (RemoteException ignored) {
        }
        try {
            Shizuku.unbindUserService(serviceArgs, connection, true);
        } catch (Throwable ignored) {
        }
        service = null;
    }

    private void handleLine(String line) {
        Matcher m;

        if ((m = SPEED_NAMED.matcher(line)).find() || (m = SPEED_KV.matcher(line)).find()) {
            float raw = parseFloat(m.group(1));
            DialView.pushLiveSpeed(raw);
            noteFirstSeen(Prop.SPEED, "speed (raw value " + raw + " - UNIT NOT CONFIRMED YET: "
                    + "check this against the stock speedo before trusting it; SmartSplit treats "
                    + "it as km/h but that hasn't been independently verified on this car)");
        }
        if ((m = RPM_NAMED.matcher(line)).find() || (m = RPM_KV.matcher(line)).find()) {
            DialView.pushLiveRpm(parseFloat(m.group(1)));
            noteFirstSeen(Prop.RPM, "RPM");
        }
        if ((m = FUEL_KV.matcher(line)).find()) {
            // remain_fuel_percentage alone isn't a range in km - kept as a status note; the
            // RANGE_*_KV patterns below are what feed DialView's range panel.
            noteFirstSeen(Prop.FUEL, "fuel % (" + m.group(1) + "% - not yet mapped to a range panel)");
        }
        // Most specific first: on a hybrid, main_tank/sub_tank/ev readings are each their own
        // key, so only fall back to the generic remain_odometer if none of those three appear on
        // this build. First on-car reading (580 km) lined up closely with the stock dash's own
        // range figure, which both confirms the key family really does mean remaining range and
        // resolves the old "could this mean something else, like a service distance" caveat.
        if ((m = RANGE_MAIN_TANK_KV.matcher(line)).find()) {
            DialView.pushLiveRangeKm(parseFloat(m.group(1)));
            noteFirstSeen(Prop.RANGE_MAIN_TANK, "main tank range (\"main_tank_remain_odometer\")");
        } else if ((m = RANGE_SUB_TANK_KV.matcher(line)).find()) {
            DialView.pushLiveRangeKm(parseFloat(m.group(1)));
            noteFirstSeen(Prop.RANGE_SUB_TANK, "sub tank range (\"sub_tank_remain_odometer\")");
        } else if ((m = RANGE_EV_KV.matcher(line)).find()) {
            DialView.pushLiveRangeKm(parseFloat(m.group(1)));
            noteFirstSeen(Prop.RANGE_EV, "EV range (\"ev_remain_odometer_status\")");
        } else if ((m = RANGE_GENERIC_KV.matcher(line)).find()) {
            DialView.pushLiveRangeKm(parseFloat(m.group(1)));
            noteFirstSeen(Prop.RANGE_GENERIC, "range (\"remain_odometer\" - generic key, no "
                    + "tank/EV-specific reading seen on this build)");
        }
        if ((m = TEMP_KV.matcher(line)).find()) {
            DialView.pushLiveTempC(parseFloat(m.group(1)));
            noteFirstSeen(Prop.TEMP, "outside temp");
        }
        if ((m = GEAR_KV.matcher(line)).find()) {
            DialView.pushLiveGear(m.group(1));
            noteFirstSeen(Prop.GEAR, "gear (raw value \"" + m.group(1) + "\" - may need label mapping once we see real values)");
        }
    }

    private void noteFirstSeen(Prop p, String label) {
        if (seen.add(p)) log("First live " + label + " reading received from GWM logcat stream.");
    }

    private static float parseFloat(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** Avoids a hard compile dependency on the generated BuildConfig class just for the package
     *  name, which is already fixed and known for this project. */
    private static final class BuildConfigShim {
        static final String APPLICATION_ID = "com.clusterprobe";
    }
}
