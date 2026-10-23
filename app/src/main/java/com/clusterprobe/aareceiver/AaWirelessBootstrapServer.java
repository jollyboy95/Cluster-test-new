package com.clusterprobe.aareceiver;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;

import com.clusterprobe.aareceiver.proto.AaWirelessStartRequestProto.AaWirelessStartRequest;
import com.clusterprobe.aareceiver.proto.AaWirelessInfoResponseProto.AaWirelessInfoResponse;
import com.clusterprobe.aareceiver.proto.AaWirelessInfoResponseProto.AaWirelessSecurityMode;
import com.clusterprobe.aareceiver.proto.AaWirelessInfoResponseProto.AaWirelessAccessPointType;

/**
 * Orchestrates the real Android-Auto-Wireless bootstrap sequence, reimplemented in Java directly
 * against aa-proxy-rs's actual working head-unit-role code (send_params() in src/bluetooth.rs) -
 * see AaWirelessConstants's doc comment for the full confirmed sequence and sourcing.
 *
 * Sequence this class runs:
 *   1. Host a local Wi-Fi AP (AaWirelessHotspot - WifiManager.startLocalOnlyHotspot).
 *   2. Open a TCP ServerSocket (port 0 - let the OS pick a free one) for the real AA session.
 *   3. Advertise + accept one Bluetooth RFCOMM connection on AaWirelessConstants.AAWG_PROFILE_UUID
 *      (the phone must already be Bluetooth-paired with this device the normal way first - this
 *      does not do discovery/pairing, same "don't touch the factory pairing flow" boundary this
 *      project has kept throughout).
 *   4. Run the bootstrap message exchange over that RFCOMM socket (AaWirelessConstants framing +
 *      the two vendored proto messages).
 *   5. Accept the phone's resulting TCP connection on the ServerSocket from step 2.
 *
 * Deliberately stops there for this increment: the accepted TCP Socket is handed to the
 * StatusListener as a successful ConnectionStage.WIRELESS_BOOTSTRAP result and then closed - NOT
 * yet wired into AaProtocol's VersionRequest/VersionResponse exchange the way the USB path already
 * is. That's the natural next increment once this bootstrap itself has been confirmed working
 * on-car (AaProtocol's read/write methods are USB-endpoint-specific right now; generalizing them
 * to also accept a Socket's InputStream/OutputStream is a small, separate, low-risk change best
 * done once there's a real accepted Socket to test it against, consistent with this project's
 * build-in-stages practice).
 *
 * Disabled by default via the same AndroidAutoReceiver.enabled flag the USB path uses - this is
 * one feature with two transports, not two independent features, so one master switch covers
 * both, same as ConnectionStage.WIRELESS_BOOTSTRAP already being listed alongside the USB stages.
 */
public final class AaWirelessBootstrapServer {

    private static AaWirelessBootstrapServer instance;
    private volatile boolean stopRequested = false;

    private BluetoothServerSocket btServerSocket;
    private ServerSocket tcpServerSocket;
    private final AaWirelessHotspot hotspot = new AaWirelessHotspot();

    public static void run(Context context, AndroidAutoReceiver.StatusListener status) {
        stop();
        instance = new AaWirelessBootstrapServer();
        Context appCtx = context.getApplicationContext();
        Thread t = new Thread(() -> instance.doRun(appCtx, status), "aa-wireless-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        if (instance != null) {
            instance.stopRequested = true;
            instance.cleanup();
        }
        instance = null;
    }

    private void log(AndroidAutoReceiver.StatusListener status, String msg) {
        if (status != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> status.onStatus(msg));
        }
    }

    private void setStage(AndroidAutoReceiver.StatusListener status, AndroidAutoReceiver.StageStatus s) {
        if (status != null) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> status.onStageUpdate(ConnectionStage.WIRELESS_BOOTSTRAP, s));
        }
    }

    private void doRun(Context context, AndroidAutoReceiver.StatusListener status) {
        log(status, "--- Wireless AA bootstrap (experimental, research probe) ---");
        if (!AndroidAutoReceiver.enabled) {
            log(status, "Wireless bootstrap: master toggle is OFF - enable the AA receiver toggle "
                    + "first (off by default on purpose, same switch the USB path uses).");
            return;
        }
        setStage(status, AndroidAutoReceiver.StageStatus.RUNNING);

        try {
            // Step 1: host our own Wi-Fi AP.
            log(status, "Starting local Wi-Fi hotspot...");
            HotspotResult hs = startHotspotAndWait(context, status);
            if (!hs.success) {
                log(status, "Hotspot failed: " + hs.detail);
                setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
                return;
            }
            log(status, "Hotspot up: ssid=" + hs.ssid + " bssid=" + (hs.bssid.isEmpty() ? "(none)" : hs.bssid));
            if (stopRequested) return;

            // Step 2: open the TCP server socket the phone will connect back to.
            tcpServerSocket = new ServerSocket(0);
            int tcpPort = tcpServerSocket.getLocalPort();
            String ourIp = findHotspotIpv4();
            if (ourIp == null) {
                log(status, "Could not determine our own IPv4 address on the hotspot interface - "
                        + "aborting (the phone needs a real IP to connect back to).");
                setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
                return;
            }
            log(status, "AA TCP session will listen on " + ourIp + ":" + tcpPort);
            if (stopRequested) return;

            // Step 3: advertise + accept the Bluetooth RFCOMM bootstrap connection.
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                log(status, "Bluetooth adapter unavailable or disabled.");
                setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
                return;
            }
            log(status, "Listening for RFCOMM connection on AAWG UUID "
                    + AaWirelessConstants.AAWG_PROFILE_UUID + " - phone must already be "
                    + "Bluetooth-paired and attempt a wireless AA connection now.");
            btServerSocket = adapter.listenUsingRfcommWithServiceRecord(
                    AaWirelessConstants.RFCOMM_SERVICE_NAME, AaWirelessConstants.AAWG_PROFILE_UUID);
            BluetoothSocket phoneBt = btServerSocket.accept(); // blocks until phone connects, or close() cancels it
            log(status, "RFCOMM connected: " + safeDeviceLabel(phoneBt));
            if (stopRequested) return;

            // Step 4: the bootstrap exchange itself.
            boolean exchangeOk = runBootstrapExchange(phoneBt, ourIp, tcpPort, hs, status);
            if (!exchangeOk) {
                setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
                return;
            }
            if (stopRequested) return;

            // Step 5: accept the phone's resulting TCP connection on our own Wi-Fi.
            tcpServerSocket.setSoTimeout(15000);
            log(status, "Bootstrap exchange succeeded - waiting up to 15s for the phone's TCP "
                    + "connection on " + ourIp + ":" + tcpPort + "...");
            Socket aaSession;
            try {
                aaSession = tcpServerSocket.accept();
            } catch (IOException timeout) {
                log(status, "No TCP connection arrived from the phone within 15s: " + timeout);
                setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
                return;
            }
            log(status, "Phone opened the AA TCP session from " + aaSession.getRemoteSocketAddress()
                    + " - wireless bootstrap fully confirmed working. (Not yet wired into the "
                    + "VersionRequest/VersionResponse exchange - that's the next increment; closing "
                    + "this test connection now.)");
            aaSession.close();
            setStage(status, AndroidAutoReceiver.StageStatus.TESTED_WORKING);

        } catch (IOException e) {
            log(status, "Wireless bootstrap failed: " + e);
            setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
        } finally {
            cleanup();
        }
    }

    private boolean runBootstrapExchange(BluetoothSocket phoneBt, String ourIp, int tcpPort,
                                          HotspotResult hs, AndroidAutoReceiver.StatusListener status)
            throws IOException {
        InputStream in = phoneBt.getInputStream();
        OutputStream out = phoneBt.getOutputStream();

        log(status, "Sending AaWirelessStartRequest (ip=" + ourIp + " port=" + tcpPort + ")...");
        AaWirelessStartRequest startReq = AaWirelessStartRequest.newBuilder()
                .setIpAddress(ourIp)
                .setPort(tcpPort)
                .build();
        AaWirelessConstants.writeFrame(out, AaWirelessConstants.MESSAGE_ID_WIFI_START_REQUEST,
                startReq.toByteArray());

        log(status, "Waiting for WifiInfoRequest...");
        AaWirelessConstants.BootstrapMessage infoReq = AaWirelessConstants.readFrame(in);
        if (infoReq.messageId != AaWirelessConstants.MESSAGE_ID_WIFI_INFO_REQUEST) {
            log(status, "Expected WifiInfoRequest, got "
                    + AaWirelessConstants.messageName(infoReq.messageId) + " - continuing anyway "
                    + "(matches aa-proxy-rs's own lenient id handling, which only warns, never aborts).");
        }

        log(status, "Sending AaWirelessInfoResponse (ssid=" + hs.ssid + ")...");
        AaWirelessInfoResponse infoResp = AaWirelessInfoResponse.newBuilder()
                .setSsid(hs.ssid)
                .setKey(hs.passphrase)
                .setBssid(hs.bssid)
                .setSecurityMode(AaWirelessSecurityMode.AA_WIRELESS_SECURITY_WPA2_PERSONAL)
                .setAccessPointType(AaWirelessAccessPointType.AA_WIRELESS_AP_DYNAMIC)
                .build();
        AaWirelessConstants.writeFrame(out, AaWirelessConstants.MESSAGE_ID_WIFI_INFO_RESPONSE,
                infoResp.toByteArray());

        log(status, "Waiting for WifiStartResponse...");
        AaWirelessConstants.BootstrapMessage startResp = AaWirelessConstants.readFrame(in);
        if (startResp.messageId != AaWirelessConstants.MESSAGE_ID_WIFI_START_RESPONSE) {
            log(status, "Expected WifiStartResponse, got "
                    + AaWirelessConstants.messageName(startResp.messageId) + ".");
        }

        log(status, "Waiting for WifiConnectStatus...");
        AaWirelessConstants.BootstrapMessage connectStatus = AaWirelessConstants.readFrame(in);
        boolean ok = AaWirelessConstants.isWifiConnectStatusSuccess(connectStatus.body);
        log(status, "WifiConnectStatus: " + (ok ? "success" : "FAILURE") + " (raw body length="
                + connectStatus.body.length + ")");
        return ok;
    }

    private static final class HotspotResult {
        boolean success;
        String ssid = "";
        String passphrase = "";
        String bssid = "";
        String detail = "";
    }

    private HotspotResult startHotspotAndWait(Context context, AndroidAutoReceiver.StatusListener status) {
        HotspotResult result = new HotspotResult();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        hotspot.start(context, new AaWirelessHotspot.Listener() {
            @Override
            public void onStarted(String ssid, String passphrase, String bssid) {
                result.success = true;
                result.ssid = ssid;
                result.passphrase = passphrase;
                result.bssid = bssid;
                latch.countDown();
            }

            @Override
            public void onFailed(String reason) {
                result.success = false;
                result.detail = reason;
                latch.countDown();
            }

            @Override
            public void onStopped() {
                // no-op here; stop() path handles cleanup logging separately
            }
        });
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                result.success = false;
                result.detail = "timed out waiting for hotspot to start";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.success = false;
            result.detail = "interrupted";
        }
        return result;
    }

    /** Finds our own IPv4 address on whichever interface the LocalOnlyHotspot AP is using. No
     *  direct API tells us the AP's interface name, so (same heuristic other open-source
     *  Android-Auto-wireless hosts use absent that API) this takes the first non-loopback, up,
     *  IPv4-bearing interface whose address is in a private range - prefers the modern
     *  192.168.49.x local-only-hotspot range or the legacy 192.168.43.x tether range if present,
     *  else falls back to the first private address found. */
    private static String findHotspotIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip.startsWith("192.168.49.") || ip.startsWith("192.168.43.")) {
                        return ip;
                    }
                    if (fallback == null && isPrivateIpv4(ip)) {
                        fallback = ip;
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through to whatever we found, or null
        }
        return fallback;
    }

    private static boolean isPrivateIpv4(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.")
                || ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    private static String safeDeviceLabel(BluetoothSocket socket) {
        try {
            android.bluetooth.BluetoothDevice device = socket.getRemoteDevice();
            return device.getAddress();
        } catch (Exception e) {
            return "(unknown device)";
        }
    }

    private void cleanup() {
        try {
            if (btServerSocket != null) btServerSocket.close();
        } catch (IOException ignored) {
        }
        btServerSocket = null;
        try {
            if (tcpServerSocket != null) tcpServerSocket.close();
        } catch (IOException ignored) {
        }
        tcpServerSocket = null;
        hotspot.stop();
    }
}
