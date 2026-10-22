package com.clusterprobe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads live vehicle data via the STANDARD Android Automotive API
 * (android.car.Car / CarPropertyManager / VehiclePropertyIds) - not GWM's private adapter
 * service. Done entirely through reflection so the project doesn't need the android.car stub
 * jar on the build classpath (it isn't part of the plain Android SDK that GitHub Actions
 * installs, only the Automotive SDK add-on). Every failure (missing class, missing property on
 * this platform, denied permission) is reported through the status callback instead of
 * crashing or silently doing nothing - DialView only shows a value once it has genuinely
 * received one, it never fabricates one.
 *
 * Properties subscribed, each independently (one missing/denied doesn't block the rest):
 *   PERF_VEHICLE_SPEED      -> DialView.pushLiveSpeed()       needs CAR_SPEED
 *   ENGINE_RPM               -> DialView.pushLiveRpm()         needs CAR_ENGINE_DETAILED
 *   RANGE_REMAINING          -> DialView.pushLiveRangeKm()      needs CAR_ENERGY
 *   ENV_OUTSIDE_TEMPERATURE  -> DialView.pushLiveTempC()        needs CAR_EXTERIOR_ENVIRONMENT
 *   GEAR_SELECTION           -> DialView.pushLiveGear()         needs CAR_POWERTRAIN
 */
public class VehicleDataSource {

    public interface StatusListener { void onStatus(String msg); }

    private static VehicleDataSource instance;
    public static boolean isRunning() { return instance != null; }

    private Object car;          // android.car.Car
    private Object propertyMgr;  // android.car.hardware.property.CarPropertyManager
    private Object listenerProxy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, PropHandler> handlers = new HashMap<>();
    private Map<Integer, String> gearLabels;

    private interface PropHandler { void onValue(Object value); }

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
            Class<?> callbackClass = Class.forName(
                    "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
            Class<?> carPropValueClass = Class.forName("android.car.hardware.CarPropertyValue");
            Method getPropertyId = carPropValueClass.getMethod("getPropertyId");
            Method getValue = carPropValueClass.getMethod("getValue");

            gearLabels = loadGearLabels();

            listenerProxy = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("onChangeEvent".equals(name) && args != null && args.length == 1 && args[0] != null) {
                            Object cpv = args[0];
                            int pid = (int) getPropertyId.invoke(cpv);
                            Object val = getValue.invoke(cpv);
                            PropHandler h = handlers.get(pid);
                            if (h != null && val != null) h.onValue(val);
                        } else if ("onErrorEvent".equals(name)) {
                            log(status, "CarPropertyManager reported an error event for a subscribed property.");
                        }
                        return null;
                    });

            Method registerCallback = propertyMgr.getClass()
                    .getMethod("registerCallback", callbackClass, int.class, float.class);

            subscribe(status, registerCallback, vpiClass, "PERF_VEHICLE_SPEED", 5f,
                    v -> DialView.pushLiveSpeed(((Number) v).floatValue() * 3.6f)); // m/s -> km/h

            subscribe(status, registerCallback, vpiClass, "ENGINE_RPM", 5f,
                    v -> DialView.pushLiveRpm(((Number) v).floatValue()));

            subscribe(status, registerCallback, vpiClass, "RANGE_REMAINING", 0.2f,
                    v -> DialView.pushLiveRangeKm(((Number) v).floatValue() / 1000f)); // m -> km

            subscribe(status, registerCallback, vpiClass, "ENV_OUTSIDE_TEMPERATURE", 0.1f,
                    v -> DialView.pushLiveTempC(((Number) v).floatValue()));

            subscribe(status, registerCallback, vpiClass, "GEAR_SELECTION", 0.1f,
                    v -> {
                        int g = ((Number) v).intValue();
                        String label = gearLabels != null ? gearLabels.get(g) : null;
                        DialView.pushLiveGear(label != null ? label : ("GEAR(" + g + ")"));
                    });

        } catch (ClassNotFoundException e) {
            log(status, "android.car classes aren't present on this build at all: " + e);
        } catch (SecurityException e) {
            log(status, "Denied: " + e + "  (a android.car.permission.CAR_* permission needs to be "
                    + "actually granted, not just declared).");
        } catch (Throwable t) {
            log(status, "Vehicle data hookup failed: " + t);
        }
    }

    private void subscribe(StatusListener status, Method registerCallback, Class<?> vpiClass,
                            String fieldName, float rateHz, PropHandler handler) {
        try {
            int id = vpiClass.getField(fieldName).getInt(null);
            handlers.put(id, handler);
            boolean ok = (boolean) registerCallback.invoke(propertyMgr, listenerProxy, id, rateHz);
            log(status, (ok ? "Subscribed to " : "registerCallback FAILED for ") + fieldName
                    + (ok ? "." : " (likely missing permission for this property group)."));
        } catch (NoSuchFieldException e) {
            log(status, fieldName + " isn't defined in VehiclePropertyIds on this platform - skipping.");
        } catch (Throwable t) {
            log(status, "Could not subscribe to " + fieldName + ": " + t);
        }
    }

    /** Builds {intValue -> short label} from android.car.hardware.property.VehicleGear's public
     *  static int GEAR_* fields, e.g. GEAR_PARK -> "P", GEAR_DRIVE -> "D". Returns null if that
     *  class isn't present on this platform; gear values then just show as raw ints. */
    private Map<Integer, String> loadGearLabels() {
        try {
            Class<?> vgClass = Class.forName("android.car.hardware.property.VehicleGear");
            Map<Integer, String> map = new HashMap<>();
            for (Field f : vgClass.getFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                String n = f.getName();
                if (!n.startsWith("GEAR_")) continue;
                int val = f.getInt(null);
                String short0 = n.replace("GEAR_", "");
                String label = short0.length() <= 2 ? short0 : short0.substring(0, 1); // P/R/N/D/.. first letter
                if (short0.startsWith("FIRST") || short0.startsWith("SECOND") || short0.matches("[A-Z]+_?\\d*")) {
                    // numbered/manual gears - keep it short but distinct where possible
                    label = short0.replaceAll("[^0-9]", "");
                    if (label.isEmpty()) label = short0.substring(0, Math.min(2, short0.length()));
                }
                map.put(val, label);
            }
            return map.isEmpty() ? null : map;
        } catch (Throwable t) {
            return null;
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
        handlers.clear();
    }
}
