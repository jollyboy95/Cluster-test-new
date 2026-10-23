package com.clusterprobe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * v0.40: the same standing capability SmartSplit (com.smartlink.smartsplit) and Kafo Themes
 * (com.kafo.themes) both declare - a BIND_ACCESSIBILITY_SERVICE-bound Accessibility Service,
 * class-named "DashboardService" in both of their real, user-supplied, on-car-installed APKs
 * (inspected directly this session, not guessed) - alongside SYSTEM_ALERT_WINDOW (already
 * declared/used by this project's own OverlayManager). That pairing is the common denominator
 * between the two independently-built third-party apps that both demonstrably relocate a whole
 * app's window to the cluster display on this exact car, with no Shizuku, no adb, no root -
 * confirmed by comparing their real manifests (Kafo Themes, notably, lacks SmartSplit's
 * WRITE_SECURE_SETTINGS/INJECT_EVENTS/com.gwm.android.multidisplay.permission.CHANGE_DATA, so
 * those three are not what's doing this particular trick).
 *
 * Deliberately inert for now. Our own Android Auto video does NOT need this service at all - it
 * renders through OverlayManager.showAaSession() instead, the same TYPE_APPLICATION_OVERLAY
 * window type this project's own dial overlay already uses successfully on display 2, which was
 * never subject to the ActivityOptions.setLaunchDisplayId() restriction in the first place. This
 * service exists as the same standing capability SmartSplit/Kafo both hold, for future work that
 * genuinely needs it - most plausibly relocating a THIRD-PARTY app's own window (Headunit
 * Reloaded, DiAuto) to another display without Shizuku, which would need real window-level
 * manipulation this class doesn't attempt yet. Confirming exactly how SmartSplit/Kafo do that
 * specific part would need decompiling their actual DashboardService bytecode, not just their
 * manifest - not done yet, and nothing here is copied from either app; this is an independent,
 * from-scratch implementation of the same standing Android capability both of them use.
 *
 * Off by default from the user's point of view the same way every other experimental capability
 * in this project is: declaring it in the manifest does not turn it on - the user must still
 * enable "ClusterProbe Dashboard" in system Settings > Accessibility before Android will ever
 * instantiate this class, exactly like SmartSplit/Kafo's own setup requires.
 */
public class ClusterProbeDashboardService extends AccessibilityService {

    private static final String TAG = "ClusterProbeDashboard";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.i(TAG, "ClusterProbeDashboardService connected (currently inert - see class doc).");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Currently a no-op - see class doc comment. Reserved for future cross-app window work.
    }

    @Override
    public void onInterrupt() {
    }
}
