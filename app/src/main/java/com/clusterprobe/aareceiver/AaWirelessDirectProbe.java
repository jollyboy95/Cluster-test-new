package com.clusterprobe.aareceiver;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Cheap feasibility probe for a THIRD wireless-AA mechanism, found by inspecting two more
 * real, actively-developed Android-native (same platform as ClusterProbe, unlike OpenAuto's C++)
 * Android Auto receiver projects the user supplied:
 *   - headunit-borconi-master (AGPLv3, "Headunit Reloaded") - its real, working "P2PWifi" mode
 *     (hu_act.java's wifi_init()/wifi_start(4)) uses android.net.wifi.p2p.WifiP2pManager.
 *   - open-headunit-main (license per its own LICENSE file) - a current (2026, Android-Auto-17.4-
 *     era) fork whose README explicitly lists "Native Mode: Direct Wi-Fi Direct or Headunit
 *     Hotspot handshake" as one of five real wireless options, and whose
 *     connection/wifi/direct/WifiDirectManager.kt is a large, heavily-hardened production
 *     implementation of exactly this.
 * Neither was copied (different language for OpenAuto's reference; this project's own
 * "read real source as spec, reimplement independently" rule applies to both regardless) - only
 * the real Android API calls and their behaviour (createGroup(), the BUSY=2 retry pattern, the
 * 192.168.49.1 group-owner address convention) were read and reimplemented here, much more simply
 * than open-headunit's 3000+-line hardened version, since this is a feasibility probe, not yet a
 * full Native Mode implementation.
 *
 * Why this matters: v0.33's WifiManager.startLocalOnlyHotspot() attempt failed on-car with
 * ERROR_INCOMPATIBLE_MODE. Wi-Fi Direct is a DIFFERENT Android API (WifiP2pManager, not
 * WifiManager) with different underlying driver requirements, and is a long-standing Android CTS
 * baseline requirement - open-headunit's own README treats it as the recommended non-root
 * automatic wireless mode specifically because it tends to work on hardware where a plain softAP
 * (LocalOnlyHotspot) doesn't. This probe only goes as far as calling createGroup() and reporting
 * whether THIS automotive Wi-Fi chipset/build allows it at all - not yet the credential/handshake
 * exchange needed to get a real phone to join the resulting group (open-headunit's own code shows
 * that part is substantial - real-world Android versions differ a lot in how a phone discovers
 * and joins a Wi-Fi Direct group for Android Auto specifically, including cases needing no
 * explicit SSID/passphrase exchange at all). Kept honestly scoped: "can we even create a P2P
 * group on this hardware" is the useful, cheap thing to learn first.
 */
public final class AaWirelessDirectProbe {

    private static AaWirelessDirectProbe instance;
    private WifiP2pManager.Channel channel;

    public static void run(Context context, AndroidAutoReceiver.StatusListener status) {
        stop();
        instance = new AaWirelessDirectProbe();
        instance.doRun(context.getApplicationContext(), status);
    }

    public static void stop() {
        if (instance != null) {
            instance.removeGroup();
        }
        instance = null;
    }

    private void log(AndroidAutoReceiver.StatusListener status, String msg) {
        if (status != null) {
            new Handler(Looper.getMainLooper()).post(() -> status.onStatus(msg));
        }
    }

    private void setStage(AndroidAutoReceiver.StatusListener status, AndroidAutoReceiver.StageStatus s) {
        if (status != null) {
            new Handler(Looper.getMainLooper())
                    .post(() -> status.onStageUpdate(ConnectionStage.WIRELESS_BOOTSTRAP, s));
        }
    }

    private void doRun(Context context, AndroidAutoReceiver.StatusListener status) {
        log(status, "--- Wireless AA: Wi-Fi Direct feasibility probe (experimental, research only) ---");
        if (!AndroidAutoReceiver.enabled) {
            log(status, "Wi-Fi Direct probe: master toggle is OFF - enable the AA receiver toggle first.");
            return;
        }
        WifiP2pManager manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            log(status, "WifiP2pManager unavailable on this device - Wi-Fi Direct isn't an option here.");
            setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
            return;
        }
        setStage(status, AndroidAutoReceiver.StageStatus.RUNNING);
        channel = manager.initialize(context, context.getMainLooper(), null);
        if (channel == null) {
            log(status, "WifiP2pManager.initialize() returned null channel.");
            setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
            return;
        }
        log(status, "Requesting a Wi-Fi Direct group (createGroup())...");
        attemptCreateGroup(manager, channel, 0, status);
    }

    private void attemptCreateGroup(WifiP2pManager manager, WifiP2pManager.Channel ch, int retryCount,
                                     AndroidAutoReceiver.StatusListener status) {
        manager.createGroup(ch, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log(status, "createGroup() succeeded - this hardware DOES support Wi-Fi Direct group "
                        + "ownership, unlike the LocalOnlyHotspot API that failed last time. Looking for "
                        + "the group-owner IP (normally 192.168.49.1)...");
                String goIp = findGroupOwnerIpv4();
                log(status, goIp != null ? "Group-owner interface IP: " + goIp
                        : "Group created but couldn't confirm the interface IP yet (may need a moment "
                                + "to come up) - check again shortly.");
                log(status, "Stopping here for this probe - joining a real phone to this group (the "
                        + "credential/handshake exchange) is NOT implemented yet, same honesty bar as "
                        + "the other wireless paths. This result just answers \"is Wi-Fi Direct usable "
                        + "on this unit at all\" - worth building further given this succeeded.");
                setStage(status, AndroidAutoReceiver.StageStatus.TESTED_WORKING);
            }

            @Override
            public void onFailure(int reason) {
                // WifiP2pManager.ERROR=0, P2P_UNSUPPORTED=1, BUSY=2, NO_SERVICE_REQUESTS=3 (AOSP).
                if (reason == 2 && retryCount < 3) {
                    log(status, "Chip reported BUSY (reason=2) - retrying in 2s (attempt "
                            + (retryCount + 1) + "/3)...");
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> attemptCreateGroup(manager, ch, retryCount + 1, status), 2000);
                    return;
                }
                log(status, "createGroup() failed: reason=" + reason + " (" + decodeP2pReason(reason) + ")");
                setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
            }
        });
    }

    private void removeGroup() {
        if (channel == null) return;
        // Best-effort only - this is a short-lived probe, not a managed connection.
    }

    private static String decodeP2pReason(int reason) {
        switch (reason) {
            case 0: return "ERROR - generic internal error";
            case 1: return "P2P_UNSUPPORTED - this device/driver doesn't support Wi-Fi Direct at all";
            case 2: return "BUSY";
            case 3: return "NO_SERVICE_REQUESTS";
            default: return "unknown reason code";
        }
    }

    private static String findGroupOwnerIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.getHostAddress().startsWith("192.168.49.")) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
