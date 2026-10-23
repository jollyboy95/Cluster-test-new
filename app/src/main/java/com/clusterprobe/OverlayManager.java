package com.clusterprobe;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.WindowManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws DialView as a real TYPE_APPLICATION_OVERLAY window on a given display, instead of as a
 * normal Activity window.
 *
 * Why this exists: a plain Activity window sits in the ordinary window z-order, which on this
 * head unit appears to be BELOW whatever persistent system overlay GWM uses for its own nav/HUD
 * graphics - launching ClusterActivity put our dials visually behind the stock nav arrows rather
 * than above them. TYPE_APPLICATION_OVERLAY is the same window type SmartSplit itself uses (per
 * the earlier APK inspection) specifically because overlay windows sit above regular activity
 * windows. Requires the SYSTEM_ALERT_WINDOW permission declared in the manifest to actually be
 * granted - on some builds that's automatic, on others the user has to flip "Display over other
 * apps" / "Draw over other apps" for this app in system Settings first.
 */
public class OverlayManager {

    private static final Map<Integer, android.view.View> active = new HashMap<>();

    public interface Result { void ok(); void fail(String reason); }

    public static void show(Context appContext, int displayId, Result result) {
        try {
            DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
            Display target = dm.getDisplay(displayId);
            if (target == null) {
                result.fail("No such displayId: " + displayId);
                return;
            }
            Context displayContext = appContext.createDisplayContext(target);
            WindowManager wm = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);

            DialView view = new DialView(displayContext);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);

            hide(displayId); // replace any existing overlay on this display first
            wm.addView(view, lp);
            active.put(displayId, view);
            result.ok();
        } catch (Throwable t) {
            result.fail(t.toString()
                    + (t instanceof SecurityException
                        ? "  (this app likely doesn't have the \"draw over other apps\" / SYSTEM_ALERT_WINDOW "
                          + "permission actually granted yet - check system Settings for ClusterProbe)."
                        : ""));
        }
    }

    /**
     * v0.40: hosts the Android Auto decode surface (AaSessionOverlayView) as a
     * TYPE_APPLICATION_OVERLAY window on the given display, exactly the same mechanism show()
     * above already uses successfully for the dial overlay - see AaSessionOverlayView's own doc
     * comment for why this sidesteps the Shizuku/ActivityOptions route entirely. Takes ownership
     * of the connected Socket (same handoff convention AndroidAutoSessionActivity's static
     * pendingSocket field uses, just passed directly here since there's no Activity launch/
     * re-creation step in between to require a static field).
     */
    public static void showAaSession(Context appContext, int displayId, java.net.Socket socket, Result result) {
        try {
            DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
            Display target = dm.getDisplay(displayId);
            if (target == null) {
                result.fail("No such displayId: " + displayId);
                return;
            }
            Context displayContext = appContext.createDisplayContext(target);
            WindowManager wm = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);

            com.clusterprobe.aareceiver.AaSessionOverlayView view =
                    new com.clusterprobe.aareceiver.AaSessionOverlayView(displayContext, socket);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            // Deliberately NOT FLAG_NOT_TOUCHABLE, unlike the dial overlay above - if this ever
            // needs to pass touch through to whatever's underneath, that can be revisited then.

            hide(displayId); // replace any existing overlay on this display first (stops it first - see below)
            wm.addView(view, lp);
            active.put(displayId, view);
            result.ok();
        } catch (Throwable t) {
            result.fail(t.toString()
                    + (t instanceof SecurityException
                        ? "  (this app likely doesn't have the \"draw over other apps\" / SYSTEM_ALERT_WINDOW "
                          + "permission actually granted yet - check system Settings for ClusterProbe)."
                        : ""));
        }
    }

    public static void hide(int displayId) {
        android.view.View v = active.remove(displayId);
        if (v == null) return;
        try {
            if (v instanceof com.clusterprobe.aareceiver.AaSessionOverlayView) {
                ((com.clusterprobe.aareceiver.AaSessionOverlayView) v).stopSession();
            }
            WindowManager wm = (WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }

    public static void hideAll() {
        for (Integer id : active.keySet().toArray(new Integer[0])) hide(id);
    }

    public static boolean isActive(int displayId) {
        return active.containsKey(displayId);
    }
}
