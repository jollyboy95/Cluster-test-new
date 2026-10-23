package com.clusterprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Read-only diagnostic for the "safe next step" from docs/H7_DISPLAY_ARCHITECTURE.md: does
 * GWM's factory cluster-renderer stack expose anything a normal, non-privileged app can reach
 * through the STANDARD Android Automotive instrument-cluster/navigation API, or is it only
 * reachable by GWM's own InstrumentClusterRenderingServiceImpl?
 *
 * Two independent checks, both read-only, both via reflection (same reasoning as
 * VehicleDataSource - no android.car stub jar on the Gradle build classpath):
 *
 * 1. android.car.navigation.CarNavigationStatusManager.getInstrumentClusterInfo() - a public,
 *    non-privileged read call (needs android.car.permission.CAR_NAVIGATION_MANAGER, confirmed
 *    "normal" protection level in AOSP) that reports whatever cluster capabilities/dimensions
 *    the currently-registered renderer (GWM's own service) has advertised to car service. This
 *    never sends navigation data anywhere - sendNavigationStateChange() is deliberately NOT
 *    called, since that would inject into whatever real guidance display is live right now.
 *
 * 2. A direct bindService() attempt against GWM's own
 *    com.gwm.android.multidisplay.InstrumentClusterRenderingServiceImpl - confirmed by manifest
 *    extraction (see docs/H7_DISPLAY_ARCHITECTURE.md #2) to require the privileged
 *    android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE. This is a permission
 *    CHECK, not a bypass attempt - ClusterProbe is a normal app and is EXPECTED to be denied.
 *    If the bind somehow succeeded, the connection is unbound immediately and nothing is ever
 *    called, read, or written through it.
 *
 * Nothing here touches car.ipk_setting.*, vehicle coding, display ordering, or any
 * signature-protected write path. It only asks standard AAOS questions and reports exactly
 * what came back.
 */
public class NavigationRendererProbe {

    public interface StatusListener { void onStatus(String msg); }

    private static final String GWM_CLUSTER_RENDERER_PKG = "com.gwm.android.multidisplay";
    private static final String GWM_CLUSTER_RENDERER_CLS =
            "com.gwm.android.multidisplay.InstrumentClusterRenderingServiceImpl";

    private static final String[] CANDIDATE_ANDROID_CAR_CLASSES = new String[] {
            "android.car.Car",
            "android.car.navigation.CarNavigationStatusManager",
            "android.car.cluster.CarInstrumentClusterManager",
            "android.car.cluster.renderer.NavigationRenderer",
            "android.car.cluster.renderer.NavigationRenderer$RendererInfo",
            "android.car.cluster.renderer.NavigationRenderer$Callback",
            "android.car.cluster.renderer.NavigationRenderer$NavigationState"
    };

    private static NavigationRendererProbe instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context appContext;
    private volatile ServiceConnection bindConnection;

    public static void run(Context context, StatusListener status) {
        stop();
        instance = new NavigationRendererProbe();
        Context appCtx = context.getApplicationContext();
        // v0.23: this probe now does a real, blocking wait for a car_service connection (up to
        // ~8s across two createCar(...) overloads) plus an optional ~6s logcat dump - both of
        // which used to run directly on whatever thread called run(), which in MainActivity is
        // the button's onClick on the MAIN thread. That's squarely in ANR territory (Android's
        // input-dispatch ANR threshold is 5s). Running the whole probe on its own background
        // thread fixes that; every status line still reaches the UI safely via log()'s
        // main.post(...), same as before.
        Thread t = new Thread(() -> instance.doRun(appCtx, status), "navigation-renderer-probe");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        if (instance == null) return;
        instance.doStop();
        instance = null;
    }

    private void log(StatusListener status, String msg) {
        if (status != null) main.post(() -> status.onStatus(msg));
    }

    private void doRun(Context context, StatusListener status) {
        appContext = context;
        log(status, "--- NavigationRenderer / cluster-renderer probe (read-only) ---");
        probePermissionProtectionLevels(context, status);
        probeAvailableAndroidCarClasses(status);

        // v0.22: the v0.21 run showed every getCarManager() call failing with
        // "IllegalStateException: not connected" - that's not a permission result, it's this
        // probe calling createCar(Context, ServiceConnection) with a null ServiceConnection and
        // then using the Car object before any real connection could be established (this build
        // only has the async two/three-arg overloads, no synchronous single-arg createCar).
        // Fixed by connecting ONCE here, for real, with a real ServiceConnection and a bounded
        // wait, then sharing that single connected Car instance across every check below -
        // which also means only one connect/disconnect cycle instead of three.
        Object car = null;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            car = connectCarAndWait(carClass, context, status);
            if (car == null) {
                log(status, "Car-connect: could not establish a connected Car instance on this build - "
                        + "skipping the manager-dependent checks below (all-managers sweep, cluster-manager "
                        + "detail, nav-focus-owner, cluster-info). This is a real result in its own right, "
                        + "not a stand-in for a permission denial.");
            } else {
                probeAllCarManagers(carClass, car, status);
                probeClusterManagerDetail(carClass, car, status);
                probeNavigationFocusOwner(carClass, car, status);
                probeInstrumentClusterInfo(carClass, car, status);
            }
        } catch (Throwable t) {
            log(status, "Car-connect: android.car.Car unavailable: " + shortCause(t));
        } finally {
            disconnectQuietly(car);
        }

        // v0.23: if Car-connect above timed out, that's a real result (no normal app can connect
        // to car_service on this build) but it came with no exception at all to explain WHY -
        // android.car.Car's connection doesn't fail the same way a denied bindService() does.
        // Grab a one-shot logcat snapshot (via Shizuku, same mechanism as the live vehicle-data
        // feed) filtered for car-service/permission-denial keywords - this is run regardless of
        // whether Car-connect succeeded, since it's cheap and the denial reason (if any) may have
        // been logged by the system at bind time either way.
        logCarServiceLogSnapshot(status);

        probeBindToFactoryRenderer(context, status);
    }

    private void logCarServiceLogSnapshot(StatusListener status) {
        log(status, "Car-service logcat snapshot: capturing recent system log for car-service/"
                + "permission-denial keywords (via Shizuku, read-only)...");
        CarServiceLogSnapshot.Result snap;
        try {
            snap = CarServiceLogSnapshot.capture();
        } catch (Throwable t) {
            log(status, "Car-service logcat snapshot failed: " + shortCause(t));
            return;
        }
        if (snap.note != null) {
            log(status, "Car-service logcat snapshot: " + snap.note);
        }
        if (snap.totalLinesScanned > 0) {
            log(status, "Car-service logcat snapshot: scanned " + snap.totalLinesScanned
                    + " recent lines, " + snap.matchingLines.size() + " matched car-service/denial keywords.");
            if (snap.matchingLines.isEmpty()) {
                log(status, "Car-service logcat snapshot: no matching lines - either nothing "
                        + "relevant was logged, or it had already scrolled out of the buffer.");
            } else {
                for (String line : snap.matchingLines) {
                    log(status, "Car-service logcat snapshot: " + line);
                }
            }
        }
    }

    /**
     * Cheap, read-only PackageManager lookups - no Car connection needed. Tells us the real
     * protectionLevel of the permissions this probe cares about, same approach as MainActivity's
     * GET_DATA check, instead of relying on an AOSP source assumption. v0.21 already corrected
     * one assumption baked into this file's own original comment: CAR_NAVIGATION_MANAGER turned
     * out to be signature-protected (0x12) on this build, not "normal". v0.22 adds
     * CAR_INSTRUMENT_CLUSTER_CONTROL, which turned up in the service-constant scan and looks like
     * it may be what actually gates CarInstrumentClusterManager (a distinct class from
     * CarNavigationStatusManager, with its own registerCallback/startActivity methods - see
     * probeClusterManagerDetail below), separate from the BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE
     * permission that gates binding GWM's rendering service directly.
     */
    private void probePermissionProtectionLevels(Context context, StatusListener status) {
        logPermissionLevel(context, status, "android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE");
        logPermissionLevel(context, status, "android.car.permission.CAR_NAVIGATION_MANAGER");
        logPermissionLevel(context, status, "android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL");
    }

    /**
     * Establishes ONE real, connected android.car.Car instance, shared by every manager-dependent
     * check in this probe, instead of each check creating (and mis-using) its own. Enumerates the
     * real createCar(...) overloads on this build (confirmed: only (Context, ServiceConnection) and
     * (Context, ServiceConnection, Handler), no synchronous single-arg form), passes a REAL
     * ServiceConnection (not null), calls car.connect() if this build exposes that method
     * explicitly, then waits up to 4s for onServiceConnected() before handing the Car back. If
     * nothing connects within that window, returns null rather than a Car instance that will fail
     * every subsequent call with "not connected".
     */
    private Object connectCarAndWait(Class<?> carClass, Context context, StatusListener status) {
        List<Method> overloads = new ArrayList<>();
        for (Method m : carClass.getMethods()) {
            if (m.getName().equals("createCar") && Modifier.isStatic(m.getModifiers())) {
                overloads.add(m);
            }
        }
        if (overloads.isEmpty()) {
            log(status, "Car-connect: android.car.Car has no createCar(...) overload at all on this build.");
            return null;
        }
        StringBuilder found = new StringBuilder("Car-connect: createCar overloads on this build: ");
        for (Method m : overloads) found.append(describeParams(m)).append("  ");
        log(status, found.toString());

        for (Method m : overloads) {
            Class<?>[] paramTypes = m.getParameterTypes();
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] connectedFlag = {false};
            ServiceConnection waiter = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    connectedFlag[0] = true;
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    latch.countDown();
                }
            };
            try {
                Object[] args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    args[i] = (paramTypes[i] == ServiceConnection.class) ? waiter : defaultArgFor(paramTypes[i], context);
                }
                Object car = m.invoke(null, args);
                if (car == null) {
                    log(status, "Car-connect: createCar" + describeParams(m) + " returned null, trying next overload...");
                    continue;
                }

                try {
                    Method connect = car.getClass().getMethod("connect");
                    connect.invoke(car);
                } catch (NoSuchMethodException ignored) {
                    // No explicit connect() on this build - createCar(...) must auto-connect.
                } catch (Throwable t) {
                    Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                            ? t.getCause() : t;
                    log(status, "Car-connect: car.connect() threw " + shortCause(cause)
                            + " (may just mean it was already connecting).");
                }

                boolean waited;
                try {
                    waited = latch.await(4, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    waited = false;
                }

                boolean isConnected = connectedFlag[0];
                try {
                    Method isConn = car.getClass().getMethod("isConnected");
                    Object v = isConn.invoke(car);
                    if (v instanceof Boolean) isConnected = (Boolean) v;
                } catch (Throwable ignored) {
                }

                log(status, "Car-connect: createCar" + describeParams(m) + " -> " + (isConnected
                        ? "connected (onServiceConnected fired)."
                        : (waited ? "onServiceDisconnected fired before ever connecting."
                                : "timed out after 4s waiting for onServiceConnected - treating as not connected.")));

                if (isConnected) return car;
                disconnectQuietly(car);
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                        ? t.getCause() : t;
                log(status, "Car-connect: createCar" + describeParams(m) + " threw " + shortCause(cause)
                        + ", trying next overload...");
            }
        }
        return null;
    }

    /**
     * android.car.cluster.CarInstrumentClusterManager (service name CAR_INSTRUMENT_CLUSTER_SERVICE,
     * confirmed as "cluster_service" on this build) is a DIFFERENT class from
     * CarNavigationStatusManager, with its own registerCallback/unregisterCallback/startActivity
     * methods (confirmed present via probeAvailableAndroidCarClasses). startActivity in particular
     * is a standard AAOS entry point for launching an Activity ONTO the instrument cluster display
     * through the car service, rather than binding GWM's rendering service directly - a
     * meaningfully different, less-privileged path than the one probeBindToFactoryRenderer already
     * confirmed denied. This logs the real, FULL method signatures (parameter types, not just
     * names) for those three methods once a genuinely connected Car is available, so if
     * getCarManager() succeeds here we know exactly what to call next; if it's denied, that denial
     * is now a trustworthy result because the Car is actually connected.
     */
    private void probeClusterManagerDetail(Class<?> carClass, Object car, StatusListener status) {
        try {
            String serviceName = null;
            try {
                Object v = carClass.getField("CAR_INSTRUMENT_CLUSTER_SERVICE").get(null);
                if (v instanceof String) serviceName = (String) v;
            } catch (Throwable ignored) {
            }
            if (serviceName == null) {
                log(status, "Cluster-manager detail: no CAR_INSTRUMENT_CLUSTER_SERVICE constant on this build - skipping.");
                return;
            }
            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            Object mgr;
            try {
                mgr = getCarManager.invoke(car, serviceName);
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                        ? t.getCause() : t;
                log(status, "Cluster-manager detail: getCarManager(\"" + serviceName + "\") -> denied/failed: "
                        + shortCause(cause) + "  (Car was genuinely connected at this point, so this is a real "
                        + "permission result, not a timing artifact.)");
                return;
            }
            if (mgr == null) {
                log(status, "Cluster-manager detail: getCarManager(\"" + serviceName + "\") returned null.");
                return;
            }
            log(status, "Cluster-manager detail: obtained " + mgr.getClass().getName()
                    + " for real, while genuinely connected.");
            for (Method m : mgr.getClass().getMethods()) {
                String n = m.getName();
                if (n.equals("registerCallback") || n.equals("unregisterCallback") || n.equals("startActivity")) {
                    StringBuilder params = new StringBuilder();
                    for (Class<?> p : m.getParameterTypes()) {
                        if (params.length() > 0) params.append(", ");
                        params.append(p.getName());
                    }
                    log(status, "Cluster-manager detail: " + n + "(" + params + ")");
                }
            }
        } catch (Throwable t) {
            log(status, "Cluster-manager detail failed: " + shortCause(t));
        }
    }

    private void logPermissionLevel(Context context, StatusListener status, String permissionName) {
        try {
            android.content.pm.PermissionInfo info = context.getPackageManager().getPermissionInfo(permissionName, 0);
            log(status, "Permission level: " + permissionName + " protectionLevel=0x"
                    + Integer.toHexString(info.protectionLevel) + " (" + decodeProtectionLevel(info.protectionLevel) + ")"
                    + (info.packageName != null ? "  declared by " + info.packageName : ""));
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            log(status, "Permission level: " + permissionName + " isn't registered on this device at all.");
        } catch (Throwable t) {
            log(status, "Permission level: couldn't read " + permissionName + ": " + shortCause(t));
        }
    }

    /** Same base-encoding table as MainActivity.decodeProtectionLevel - duplicated locally so
     *  this class has no dependency on MainActivity for a standalone diagnostic. */
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

    /**
     * Beyond just printing the *_SERVICE string constants (which probeAvailableAndroidCarClasses
     * already does), this actually CALLS Car.getCarManager(...) for every one found and reports
     * which ones a normal app can actually obtain a manager for - the real, on-car answer to
     * "what part of android.car is reachable here" rather than just what's declared.
     */
    private void probeAllCarManagers(Class<?> carClass, Object car, StatusListener status) {
        try {
            Method getCarManager = carClass.getMethod("getCarManager", String.class);

            List<String> serviceNames = new ArrayList<>();
            for (Field f : carClass.getFields()) {
                if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getName().endsWith("_SERVICE")) continue;
                try {
                    Object v = f.get(null);
                    if (v instanceof String) serviceNames.add((String) v);
                } catch (Throwable ignored) {
                }
            }
            log(status, "All-managers sweep: trying getCarManager() for " + serviceNames.size()
                    + " declared service constants (Car is genuinely connected now)...");
            for (String name : serviceNames) {
                try {
                    Object mgr = getCarManager.invoke(car, name);
                    log(status, mgr != null
                            ? ("All-managers sweep: \"" + name + "\" -> " + mgr.getClass().getName())
                            : ("All-managers sweep: \"" + name + "\" -> null"));
                } catch (Throwable t) {
                    Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                            ? t.getCause() : t;
                    log(status, "All-managers sweep: \"" + name + "\" -> denied/failed: " + shortCause(cause));
                }
            }
        } catch (Throwable t) {
            log(status, "All-managers sweep failed: " + shortCause(t));
        }
    }

    /**
     * android.car.CarAppFocusManager is the STANDARD AAOS way to ask "which app currently owns
     * navigation focus" - a much more precise answer than guessing from running processes. Ties
     * directly to docs/H7_DISPLAY_ARCHITECTURE.md's addendum on the AA/CarPlay "borrow"
     * mechanism: whichever package owns APP_FOCUS_TYPE_NAVIGATION at probe time is the one
     * actually feeding guidance data right now.
     */
    private void probeNavigationFocusOwner(Class<?> carClass, Object car, StatusListener status) {
        try {
            String appFocusService = null;
            try {
                Object v = carClass.getField("APP_FOCUS_SERVICE").get(null);
                if (v instanceof String) appFocusService = (String) v;
            } catch (Throwable ignored) {
            }
            if (appFocusService == null) {
                log(status, "Nav-focus-owner probe: no APP_FOCUS_SERVICE constant on this build's android.car.Car - skipping.");
                return;
            }
            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            Object focusMgr = getCarManager.invoke(car, appFocusService);
            if (focusMgr == null) {
                log(status, "Nav-focus-owner probe: getCarManager(APP_FOCUS_SERVICE) returned null.");
                return;
            }
            List<String> allMethodNames = listMethodNames(focusMgr.getClass());
            log(status, "Nav-focus-owner probe: obtained " + focusMgr.getClass().getName()
                    + ". Methods (first 12): " + summarizeMethods(allMethodNames));
            // v0.25: the first-12 summary above wasn't enough to find this build's real
            // focus-owner accessor (getAppTypeOwner(int) doesn't exist here - see below). Log the
            // COMPLETE method list once, with full signatures (name + parameter types, since two
            // methods can share a name with different overloads - e.g. abandonAppFocus appeared
            // twice in the truncated list, which is exactly this case), so the real accessor name
            // on THIS platform can be read straight off the log instead of guessed at again.
            List<String> fullSignatures = new ArrayList<>();
            for (Method m : focusMgr.getClass().getMethods()) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                fullSignatures.add(m.getName() + describeParams(m));
            }
            log(status, "Nav-focus-owner probe: FULL method list (" + fullSignatures.size() + "): "
                    + String.join(", ", fullSignatures));
            // Also call out anything with "owner" in the name specifically, since that's the
            // concept we're actually looking for and it may not be called getAppTypeOwner here.
            List<String> ownerLike = new ArrayList<>();
            for (String sig : fullSignatures) {
                if (sig.toLowerCase(java.util.Locale.ROOT).contains("owner")) ownerLike.add(sig);
            }
            log(status, "Nav-focus-owner probe: method(s) with \"owner\" in the name: "
                    + (ownerLike.isEmpty() ? "<none>" : String.join(", ", ownerLike)));

            Method owner = null;
            for (Method m : focusMgr.getClass().getMethods()) {
                if (m.getName().equals("getAppTypeOwner") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == int.class) {
                    owner = m;
                    break;
                }
            }
            if (owner == null) {
                log(status, "Nav-focus-owner probe: no getAppTypeOwner(int) method on this build's "
                        + "manager - can't identify the current navigation-focus owner that way. See the "
                        + "full method list just logged above for whatever this platform calls it instead.");
                return;
            }
            int navType = 1; // CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION's documented value
            try {
                Object v = focusMgr.getClass().getField("APP_FOCUS_TYPE_NAVIGATION").get(null);
                if (v instanceof Integer) navType = (Integer) v;
            } catch (Throwable ignored) {
            }
            Object result = owner.invoke(focusMgr, navType);
            log(status, "Nav-focus-owner probe: getAppTypeOwner(APP_FOCUS_TYPE_NAVIGATION=" + navType
                    + ") = " + result + " (note which protocol/app is active when you read this - "
                    + "see docs/H7_DISPLAY_ARCHITECTURE.md section 10 on the AA/CarPlay borrow mechanism).");
        } catch (Throwable t) {
            log(status, "Nav-focus-owner probe failed: " + shortCause(t));
        }
    }

    private void disconnectQuietly(Object localCar) {
        if (localCar == null) return;
        try {
            Method d = localCar.getClass().getMethod("disconnect");
            d.invoke(localCar);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Scans for the real Android Automotive classes and service constants this head unit exposes,
     * instead of assuming one exact API layout or service name. This matters because the H7 build
     * appears to differ from the single-arg Car.createCar(...) signature that many examples use.
     */
    private void probeAvailableAndroidCarClasses(StatusListener status) {
        for (String className : CANDIDATE_ANDROID_CAR_CLASSES) {
            try {
                Class<?> clazz = Class.forName(className);
                List<String> publicMethods = new ArrayList<>();
                for (Method m : clazz.getMethods()) {
                    if (Modifier.isPublic(m.getModifiers())) publicMethods.add(m.getName());
                }
                log(status, "Interface scan: " + className + " present. Public methods: "
                        + summarizeMethods(publicMethods));
            } catch (Throwable t) {
                log(status, "Interface scan: " + className + " not available: " + shortCause(t));
            }
        }

        try {
            Class<?> carClass = Class.forName("android.car.Car");
            StringBuilder fields = new StringBuilder();
            for (Field f : carClass.getFields()) {
                if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) continue;
                String name = f.getName();
                if (name.contains("SERVICE") || name.contains("NAVIGATION") || name.contains("CLUSTER")) {
                    try {
                        Object v = f.get(null);
                        fields.append(name).append('=').append(v).append("  ");
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (fields.length() == 0) {
                log(status, "Interface scan: android.car.Car exposes no NAVIGATION/CLUSTER service constants by name on this build.");
            } else {
                log(status, "Interface scan: android.car.Car service strings: " + fields.toString().trim());
            }
        } catch (Throwable t) {
            log(status, "Interface scan: android.car.Car unavailable: " + shortCause(t));
        }
    }

    private Object defaultArgFor(Class<?> paramType, Context context) {
        if (paramType.isAssignableFrom(context.getClass())) return context;
        if (paramType == int.class) return 0;
        if (paramType == long.class) return 0L;
        if (paramType == boolean.class) return false;
        return null;
    }

    private String describeParams(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    private String summarizeMethods(List<String> names) {
        if (names.isEmpty()) return "<none>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(12, names.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        if (names.size() > 12) sb.append(", ...");
        return sb.toString();
    }

    private String shortCause(Throwable t) {
        String message = t.getMessage();
        String simple = t.getClass().getSimpleName();
        return simple + (message == null || message.trim().isEmpty() ? "" : (": " + message));
    }

    private void probeInstrumentClusterInfo(Class<?> carClass, Object car, StatusListener status) {
        try {
            String serviceName = findNavigationServiceConstant(status, carClass);
            if (serviceName == null) return;

            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            Object navMgr;
            try {
                navMgr = getCarManager.invoke(car, serviceName);
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                        ? t.getCause() : t;
                log(status, "Cluster-info probe: getCarManager(\"" + serviceName + "\") -> denied/failed: "
                        + shortCause(cause) + "  (Car was genuinely connected, so - given CAR_NAVIGATION_MANAGER's "
                        + "confirmed signature protection level - this is likely a real permission denial, "
                        + "not a timing artifact.)");
                return;
            }
            if (navMgr == null) {
                log(status, "Cluster-info probe: CarNavigationStatusManager came back null for \""
                        + serviceName + "\".");
                return;
            }
            log(status, "Cluster-info probe: obtained " + navMgr.getClass().getName() + ".");
            log(status, "Cluster-info probe: manager method names: "
                    + summarizeMethods(listMethodNames(navMgr.getClass())));

            try {
                Method getInfo = navMgr.getClass().getMethod("getInstrumentClusterInfo");
                Object info = getInfo.invoke(navMgr);
                if (info == null) {
                    log(status, "Cluster-info probe: getInstrumentClusterInfo() returned null - no "
                            + "renderer has advertised cluster capabilities via this path on this build.");
                } else {
                    log(status, "Cluster-info probe: " + describeClusterInfo(info));
                }
            } catch (NoSuchMethodException e) {
                log(status, "Cluster-info probe: getInstrumentClusterInfo() isn't present on this "
                        + "platform's CarNavigationStatusManager.");
            } catch (Throwable t) {
                log(status, "Cluster-info probe: getInstrumentClusterInfo() call failed: " + t);
            }
        } catch (Throwable t) {
            log(status, "Cluster-info probe failed: " + t);
        }
    }

    private List<String> listMethodNames(Class<?> clazz) {
        List<String> result = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (Modifier.isPublic(m.getModifiers())) result.add(m.getName());
        }
        return result;
    }

    private String findNavigationServiceConstant(StatusListener status, Class<?> carClass) {
        try {
            Field f = carClass.getField("CAR_NAVIGATION_SERVICE");
            Object v = f.get(null);
            if (v instanceof String) return (String) v;
        } catch (NoSuchFieldException ignored) {
        } catch (Throwable t) {
            log(status, "Cluster-info probe: reading CAR_NAVIGATION_SERVICE threw " + t);
        }
        for (Field f : carClass.getFields()) {
            if (f.getType() != String.class || !Modifier.isStatic(f.getModifiers())) continue;
            if (!f.getName().contains("NAVIGATION") && !f.getName().contains("CLUSTER")) continue;
            try {
                Object v = f.get(null);
                if (v instanceof String) {
                    log(status, "Cluster-info probe: CAR_NAVIGATION_SERVICE not found by name - using "
                            + f.getName() + "=\"" + v + "\" instead.");
                    return (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        log(status, "Cluster-info probe: no NAVIGATION-related car-manager service name constant "
                + "found on android.car.Car for this platform - can't run this check here.");
        return null;
    }

    private String describeClusterInfo(Object info) {
        StringBuilder sb = new StringBuilder();
        appendIntIfPresent(sb, info, "getType", "type");
        appendIntIfPresent(sb, info, "getMinIntervalMillis", "minIntervalMs");
        appendIntIfPresent(sb, info, "getImageWidth", "imageW");
        appendIntIfPresent(sb, info, "getImageHeight", "imageH");
        appendIntIfPresent(sb, info, "getImageColorDepthBits", "colorDepthBits");
        String s = sb.toString().trim();
        return s.isEmpty() ? ("cluster info reported, raw: " + info) : ("cluster info reported: " + s);
    }

    private void appendIntIfPresent(StringBuilder sb, Object obj, String methodName, String label) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            Object v = m.invoke(obj);
            sb.append(label).append('=').append(v).append(' ');
        } catch (Throwable ignored) {
        }
    }

    private void probeBindToFactoryRenderer(Context context, StatusListener status) {
        Intent intent = new Intent();
        intent.setClassName(GWM_CLUSTER_RENDERER_PKG, GWM_CLUSTER_RENDERER_CLS);

        bindConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                log(status, "Renderer-bind probe: UNEXPECTEDLY connected to " + name
                        + " - unbinding immediately; this connection is not used for anything.");
                main.post(() -> {
                    try {
                        context.unbindService(bindConnection);
                    } catch (Throwable ignored) {
                    }
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                log(status, "Renderer-bind probe: service disconnected (" + name + ").");
            }
        };

        try {
            boolean ok = context.bindService(intent, bindConnection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                log(status, "Renderer-bind probe: bindService() to InstrumentClusterRenderingServiceImpl "
                        + "returned false (not found or not bindable from a normal app here) - consistent "
                        + "with the manifest's BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE requirement.");
                bindConnection = null;
            } else {
                log(status, "Renderer-bind probe: bindService() accepted, waiting for onServiceConnected "
                        + "(a SecurityException may still arrive asynchronously from the system process)...");
            }
        } catch (SecurityException se) {
            log(status, "Renderer-bind probe: bindService() denied - SecurityException: " + se
                    + "  This confirms InstrumentClusterRenderingServiceImpl rejects a normal app, exactly "
                    + "as docs/H7_DISPLAY_ARCHITECTURE.md's manifest extraction predicted.");
            bindConnection = null;
        } catch (Throwable t) {
            log(status, "Renderer-bind probe: unexpected failure: " + t);
            bindConnection = null;
        }
    }

    private void doStop() {
        if (bindConnection != null && appContext != null) {
            try {
                appContext.unbindService(bindConnection);
            } catch (Throwable ignored) {
            }
        }
        bindConnection = null;
        appContext = null;
    }
}
