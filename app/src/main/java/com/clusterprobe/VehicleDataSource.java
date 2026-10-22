package com.clusterprobe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Reads live vehicle speed/RPM via the STANDARD Android Automotive API
 * (android.car.Car / CarPropertyManager / VehiclePropertyIds) - not GWM's private adapter
 * service. Done entirely through reflection so the project doesn't need the android.car stub
 * jar on the build classpath (it isn't part of the plain Android SDK that GitHub Actions
 * installs, only the Automotive SDK add-on). If this platform doesn't have android.car at all,
 * doesn't expose these properties, or denies the permission, every failure is reported through
 * the status callback rather than crashing - DialView just keeps showing its demo animation.
 *
 * Needs android.car.permission.CAR_SPEED (for PERF_VEHICLE_SPEED) and
 * android.car.permission.CAR_ENGINE_DETAILED (for ENGINE_RPM) granted. On some builds these are
 * normal-protection and just work once declared; on others they may be denied to a third-party
 * app - either way this reports exactly what happened instead of guessing.
 */
public class VehicleDataSource {

    public interface StatusListener { void onStatus(String msg); }

    private static VehicleDataSource instance;
    public static boolean isRunning() { return instance != null; }

    private Object car;          // android.car.Car
    private Object propertyMgr;  // android.car.hardware.property.CarPropertyManager
    private Object listenerProxy;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static void start(Context appContext, StatusListener status) {
        stop();
        instance = new VehicleDataSource();
        instance.doStart(appContext, status);
    }

    public static void stop() {
        if (instance == null) return;
        instance.doStop();
        instance = null;
        DialView.liveMode = false;
    }

    private void log(StatusListener status, String msg) {
        if (status != null) main.post(() -> status.onStatus(msg));
    }

    private void doStart(Context appContext, StatusListener status) {
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Method createCar = carClass.getMethod("createCar", Context.class);
            car = createCar.invoke(null, appContext);
            if (car == null) {
                log(status, "android.car.Car.createCar() returned null - car service not available on this build.");
                return;
            }

            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            String propertyServiceName = (String) carClass.getField("PROPERTY_SERVICE").get(null);
            propertyMgr = getCarManager.invoke(car, propertyServiceName);
            if (propertyMgr == null) {
                log(status, "Could not get CarPropertyManager (PROPERTY_SERVICE returned null) - "
                        + "permission or availability issue.");
                return;
            }

            Class<?> vpiClass = Class.forName("android.car.VehiclePropertyIds");
            int speedId = vpiClass.getField("PERF_VEHICLE_SPEED").getInt(null);
            int rpmId = -1;
            boolean hasRpm;
            try {
                rpmId = vpiClass.getField("ENGINE_RPM").getInt(null);
                hasRpm = true;
            } catch (NoSuchFieldException e) {
                hasRpm = false;
                log(status, "ENGINE_RPM isn't defined in VehiclePropertyIds on this platform - speed only.");
            }
            final int finalRpmId = rpmId;
            final boolean finalHasRpm = hasRpm;

            Class<?> callbackClass = Class.forName(
                    "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
            Class<?> carPropValueClass = Class.forName("android.car.hardware.CarPropertyValue");
            Method getPropertyId = carPropValueClass.getMethod("getPropertyId");
            Method getValue = carPropValueClass.getMethod("getValue");

            listenerProxy = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("onChangeEvent".equals(name) && args != null && args.length == 1 && args[0] != null) {
                            Object cpv = args[0];
                            int pid = (int) getPropertyId.invoke(cpv);
                            Object val = getValue.invoke(cpv);
                            if (val instanceof Number) {
                                float f = ((Number) val).floatValue();
                                if (pid == speedId) {
                                    DialView.pushLiveSpeed(f * 3.6f); // m/s -> km/h
                                } else if (finalHasRpm && pid == finalRpmId) {
                                    DialView.pushLiveRpm(f);
                                }
                            }
                        } else if ("onErrorEvent".equals(name)) {
                            log(status, "CarPropertyManager reported an error event for a subscribed property.");
                        }
                        return null;
                    });

            Method registerCallback = propertyMgr.getClass()
                    .getMethod("registerCallback", callbackClass, int.class, float.class);

            boolean speedOk = (boolean) registerCallback.invoke(propertyMgr, listenerProxy, speedId, 5f);
            log(status, speedOk
                    ? "Subscribed to PERF_VEHICLE_SPEED - live speed should start updating."
                    : "registerCallback for PERF_VEHICLE_SPEED returned false (likely missing CAR_SPEED permission).");

            if (hasRpm) {
                boolean rpmOk = (boolean) registerCallback.invoke(propertyMgr, listenerProxy, finalRpmId, 5f);
                log(status, rpmOk
                        ? "Subscribed to ENGINE_RPM - live RPM should start updating."
                        : "registerCallback for ENGINE_RPM returned false (likely missing CAR_ENGINE_DETAILED "
                          + "permission, or this hybrid doesn't expose it while on EV power).");
            }

        } catch (ClassNotFoundException e) {
            log(status, "android.car classes aren't present on this build at all: " + e);
        } catch (SecurityException e) {
            log(status, "Denied: " + e + "  (needs android.car.permission.CAR_SPEED / "
                    + "CAR_ENGINE_DETAILED actually granted, not just declared).");
        } catch (Throwable t) {
            log(status, "Vehicle data hookup failed: " + t);
        }
    }

    private void doStop() {
        try {
            if (propertyMgr != null && listenerProxy != null) {
                Method unregister = propertyMgr.getClass().getMethod("unregisterCallback",
                        Class.forName("android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback"));
                unregister.invoke(propertyMgr, listenerProxy);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (car != null) {
                Method disconnect = car.getClass().getMethod("disconnect");
                disconnect.invoke(car);
            }
        } catch (Throwable ignored) {
        }
        car = null;
        propertyMgr = null;
        listenerProxy = null;
    }
}
