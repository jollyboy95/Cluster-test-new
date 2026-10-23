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
    private ServiceConnection bindConnection;

    public static void run(Context context, StatusListener status) {
        stop();
        instance = new NavigationRendererProbe();
        instance.doRun(context.getApplicationContext(), status);
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
        probeAllCarManagers(context, status);
        probeNavigationFocusOwner(context, status);
        probeInstrumentClusterInfo(context, status);
        probeBindToFactoryRenderer(context, status);
    }

    /**
     * Cheap, read-only PackageManager lookups - no Car connection needed. Tells us the real
     * protectionLevel of the two permissions this probe cares about, same approach as
     * MainActivity's GET_DATA check, instead of relying on the AOSP source assumption in this
     * file's own class comment.
     */
    private void probePermissionProtectionLevels(Context context, StatusListener status) {
        logPermissionLevel(context, status, "android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE");
        logPermissionLevel(context, status, "android.car.permission.CAR_NAVIGATION_MANAGER");
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
    private void probeAllCarManagers(Context context, StatusListener status) {
        Object localCar = null;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            localCar = tryCreateCar(carClass, context, status);
            if (localCar == null) {
                log(status, "All-managers sweep: no Car instance available, skipping.");
                return;
            }
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
                    + " declared service constants...");
            for (String name : serviceNames) {
                try {
                    Object mgr = getCarManager.invoke(localCar, name);
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
        } finally {
            disconnectQuietly(localCar);
        }
    }

    /**
     * android.car.CarAppFocusManager is the STANDARD AAOS way to ask "which app currently owns
     * navigation focus" - a much more precise answer than guessing from running processes. Ties
     * directly to docs/H7_DISPLAY_ARCHITECTURE.md's addendum on the AA/CarPlay "borrow"
     * mechanism: whichever package owns APP_FOCUS_TYPE_NAVIGATION at probe time is the one
     * actually feeding guidance data right now.
     */
    private void probeNavigationFocusOwner(Context context, StatusListener status) {
        Object localCar = null;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            localCar = tryCreateCar(carClass, context, status);
            if (localCar == null) {
                log(status, "Nav-focus-owner probe: no Car instance available, skipping.");
                return;
            }
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
            Object focusMgr = getCarManager.invoke(localCar, appFocusService);
            if (focusMgr == null) {
                log(status, "Nav-focus-owner probe: getCarManager(APP_FOCUS_SERVICE) returned null.");
                return;
            }
            log(status, "Nav-focus-owner probe: obtained " + focusMgr.getClass().getName()
                    + ". Methods: " + summarizeMethods(listMethodNames(focusMgr.getClass())));

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
                        + "manager - can't identify the current navigation-focus owner this way.");
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
        } finally {
            disconnectQuietly(localCar);
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

    /**
     * The on-car result confirmed that android.car.Car.createCar(Context) is not present on this
     * build. This helper logs all overloads and tries them in order rather than assuming a single
     * signature and crashing with NoSuchMethodException on the first attempt.
     */
    private Object tryCreateCar(Class<?> carClass, Context context, StatusListener status) {
        List<Method> overloads = new ArrayList<>();
        for (Method m : carClass.getMethods()) {
            if (m.getName().equals("createCar") && Modifier.isStatic(m.getModifiers())) {
                overloads.add(m);
            }
        }
        if (overloads.isEmpty()) {
            log(status, "Cluster-info probe: android.car.Car has no createCar(...) overload at all on this build.");
            return null;
        }
        StringBuilder found = new StringBuilder("Cluster-info probe: createCar overloads on this build: ");
        for (Method m : overloads) found.append(describeParams(m)).append("  ");
        log(status, found.toString());

        for (Method m : overloads) {
            try {
                Class<?>[] paramTypes = m.getParameterTypes();
                Object[] args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    args[i] = defaultArgFor(paramTypes[i], context);
                }
                Object car = m.invoke(null, args);
                if (car != null) {
                    log(status, "Cluster-info probe: createCar" + describeParams(m) + " succeeded.");
                    return car;
                }
                log(status, "Cluster-info probe: createCar" + describeParams(m)
                        + " returned null, trying next overload...");
            } catch (Throwable t) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                        ? t.getCause() : t;
                log(status, "Cluster-info probe: createCar" + describeParams(m)
                        + " threw " + cause + ", trying next overload...");
            }
        }
        return null;
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

    private void probeInstrumentClusterInfo(Context context, StatusListener status) {
        Object localCar = null;
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            localCar = tryCreateCar(carClass, context, status);
            if (localCar == null) {
                log(status, "Cluster-info probe: couldn't obtain a Car instance via any createCar(...) "
                        + "overload on this build - can't run the rest of this check.");
                return;
            }

            String serviceName = findNavigationServiceConstant(status, carClass);
            if (serviceName == null) return;

            Method getCarManager = carClass.getMethod("getCarManager", String.class);
            Object navMgr;
            try {
                navMgr = getCarManager.invoke(localCar, serviceName);
            } catch (Throwable t) {
                log(status, "Cluster-info probe: getCarManager(\"" + serviceName + "\") threw " + t
                        + " - likely CAR_NAVIGATION_MANAGER not granted to this app.");
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
        } catch (ClassNotFoundException e) {
            log(status, "Cluster-info probe: android.car classes aren't present on this build: " + e);
        } catch (Throwable t) {
            log(status, "Cluster-info probe failed: " + t);
        } finally {
            if (localCar != null) {
                try {
                    Method d = localCar.getClass().getMethod("disconnect");
                    d.invoke(localCar);
                } catch (Throwable ignored) {
                }
            }
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
