package com.clusterprobe;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only probe for a lead found by static analysis of a real commercial reference app
 * ("SmartSplit", com.smartlink.smartsplit, built for Haval H9 - same GWM/Harman platform family
 * as this H7, not confirmed identical) on 2026-10-23: that app reads a system property called
 * "sys.other.keyevent_notify" to learn about physical button presses (steering wheel / cluster
 * buttons), parsing it with the regex "sys\.other\.keyevent_notify.*\[([0-9]+),\s*([0-9]+)\]" -
 * i.e. the platform appears to publish button events as "[code, value]" pairs via a system
 * property, readable with no special permission beyond hidden-API access to
 * android.os.SystemProperties (a non-SDK class every normal app is blocked from compiling
 * against directly - reached here via reflection only, same approach already used elsewhere in
 * this project for other hidden Car-service APIs).
 *
 * This is NOT a confirmed result on this car. SmartSplit targets the H9, not this H7, and this
 * class exists purely to find out whether the same property exists and updates here, by polling
 * it and logging every value and every change - read-only, nothing written anywhere, no input
 * injection, nothing touching the signature-protected GWM theme_config/hut-code interface. Do not
 * treat this as a working button-event source until a real change (e.g. a steering-wheel button
 * press reflected in the logged value) has actually been observed on this car.
 *
 * SmartSplit also tries privileged input injection first (android.hardware.input.InputManager's
 * hidden injectInputEvent/createInputForwarder methods) and logs that as "unavailable" when it
 * fails, falling back to AccessibilityService-based synthetic taps. This probe does not attempt
 * input injection or synthetic taps at all - reading button state is a separate, much smaller
 * question from acting on it, and this project doesn't claim the latter works until the former is
 * even confirmed.
 */
public class ButtonEventProbe {

    public interface StatusListener { void onStatus(String msg); }

    private static final String PROPERTY_KEY = "sys.other.keyevent_notify";
    private static final Pattern VALUE_PATTERN =
            Pattern.compile("\\[([0-9]+),\\s*([0-9]+)\\]");
    private static final long POLL_INTERVAL_MS = 300L;

    private static ButtonEventProbe instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private Thread worker;
    private String lastRawValue = null;
    private boolean haveLoggedInitial = false;
    private int changeCount = 0;

    public static void start(StatusListener status) {
        stop();
        instance = new ButtonEventProbe();
        instance.doStart(status);
    }

    public static void stop() {
        if (instance == null) return;
        instance.doStop();
        instance = null;
    }

    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    private void log(StatusListener status, String msg) {
        if (status != null) main.post(() -> status.onStatus(msg));
    }

    private void doStart(StatusListener status) {
        log(status, "Button event probe: polling system property \"" + PROPERTY_KEY + "\" every "
                + POLL_INTERVAL_MS + "ms (lead from SmartSplit, a commercial H9 app - not yet "
                + "confirmed on this H7). Read-only: no input injection, nothing written anywhere. "
                + "Logging the current raw value now, then only logging again when it changes. "
                + "Press a steering-wheel/cluster button on the car and watch for a change.");
        running = true;
        worker = new Thread(() -> doRun(status), "ButtonEventProbe");
        worker.setDaemon(true);
        worker.start();
    }

    private void doRun(StatusListener status) {
        boolean loggedReflectionFailure = false;
        boolean loggedGetpropFailure = false;
        while (running) {
            String raw = readViaReflection();
            String source = "SystemProperties (reflection)";
            if (raw == null) {
                if (!loggedReflectionFailure) {
                    log(status, "Button event probe: SystemProperties reflection unavailable or "
                            + "denied on this build; falling back to shelling out to \"getprop\" "
                            + "(same fallback SmartSplit itself uses).");
                    loggedReflectionFailure = true;
                }
                raw = readViaGetprop();
                source = "getprop (shell fallback)";
                if (raw == null && !loggedGetpropFailure) {
                    log(status, "Button event probe: getprop fallback also failed or returned "
                            + "nothing - this property may not exist on this build, or neither "
                            + "read path is available to a normal app here.");
                    loggedGetpropFailure = true;
                }
            }

            if (raw != null && !haveLoggedInitial) {
                // First successful read, even if it never changes again - so a returning user
                // can see the probe actually reached the property, not just that it's idle.
                haveLoggedInitial = true;
                lastRawValue = raw;
                String parsed = parse(raw);
                log(status, "Button event probe: initial value (via " + source + "): raw=\"" + raw
                        + "\"" + (parsed != null ? ", parsed=" + parsed : "")
                        + " (will only log again when this changes).");
            } else if (raw != null && !raw.equals(lastRawValue)) {
                changeCount++;
                lastRawValue = raw;
                String parsed = parse(raw);
                log(status, "Button event probe: value changed (#" + changeCount + ", via "
                        + source + "): raw=\"" + raw + "\"" + (parsed != null ? ", parsed=" + parsed : ""));
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Reflective android.os.SystemProperties.get(String) - a hidden/non-SDK API. */
    private String readViaReflection() {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getMethod("get", String.class);
            Object result = get.invoke(null, PROPERTY_KEY);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Public-API fallback: literally shells out to "getprop <key>", same as SmartSplit does. */
    private String readViaGetprop() {
        Process process = null;
        try {
            process = new ProcessBuilder("getprop", PROPERTY_KEY).redirectErrorStream(true).start();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor();
                return line;
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Pulls "[code, value]" out of a raw property string like SmartSplit's own regex does. */
    private String parse(String raw) {
        if (raw == null) return null;
        Matcher m = VALUE_PATTERN.matcher(raw);
        if (!m.find()) return null;
        return "code=" + m.group(1) + ", value=" + m.group(2);
    }

    private void doStop() {
        running = false;
        if (worker != null) {
            try { worker.interrupt(); } catch (Throwable ignored) { }
        }
        worker = null;
        lastRawValue = null;
        haveLoggedInitial = false;
        changeCount = 0;
    }
}
