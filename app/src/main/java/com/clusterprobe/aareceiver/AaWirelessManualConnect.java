package com.clusterprobe.aareceiver;

import android.content.Context;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A second, much simpler way to start a wireless Android Auto session - found by inspecting the
 * real OpenAuto source (f1xpl/openauto, GPLv3, same author as aasdk; user-supplied
 * openauto-development.zip, read here only as a reference spec - it's C++/Qt/Boost for
 * Linux/RaspberryPi, nothing from it is copied, same "read real source, reimplement
 * independently" approach already used for aasdk and aa-proxy-rs throughout this project).
 *
 * OpenAuto's own "Connect" dialog (src/autoapp/UI/ConnectDialog.cpp) does nothing more than open
 * a plain TCP connection to a user-typed IP address on port 5277 - no Bluetooth, no hotspot
 * hosting, nothing like AaWirelessBootstrapServer's whole RFCOMM+credential-exchange dance. Its
 * own README explains why: "Wireless (WiFi) mode via head unit server (must be enabled in hidden
 * developer settings)" - this refers to a real, well-documented hidden option in the phone's own
 * Android Auto app (reachable by repeatedly tapping the version number in Android Auto's own
 * settings to unlock its developer settings, then enabling "Start head unit server"), which makes
 * the PHONE itself listen on TCP port 5277 and display its own IP address. In that mode the phone
 * is the TCP *server* and the head unit is just an ordinary TCP *client* - the whole Bluetooth
 * RFCOMM bootstrap and Wi-Fi-AP-hosting problem AaWirelessBootstrapServer hit (ERROR_INCOMPATIBLE_
 * MODE) doesn't even enter into this path, because neither device needs to host anything: this
 * head unit and the phone just both need to already be on the same Wi-Fi network (e.g. both
 * connected to the user's home/mobile Wi-Fi, or to a hotspot either device is already hosting for
 * an unrelated reason) - port 5277 itself matches aa-proxy-rs's own TCP_DHU_PORT constant
 * ("Desktop Head Unit" - Google's own official port for exactly this kind of manual-IP wireless
 * connection), cross-confirming this isn't a guess.
 *
 * Once connected, this hands off to exactly the same AA Messenger protocol AaProtocol already
 * implements for USB - not yet wired up here either (same deferred step as
 * AaWirelessBootstrapServer), kept deliberately simple for this increment: confirm the plain TCP
 * connection itself works first.
 */
public final class AaWirelessManualConnect {

    public static final int DEFAULT_PORT = 5277;

    private static AaWirelessManualConnect instance;
    private volatile boolean stopRequested = false;
    private Socket socket;

    public static void connect(Context context, String ip, int port, AndroidAutoReceiver.StatusListener status) {
        stop();
        instance = new AaWirelessManualConnect();
        Thread t = new Thread(() -> instance.doConnect(ip, port, status), "aa-wireless-manual-connect");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        if (instance != null) {
            instance.stopRequested = true;
            instance.closeSocket();
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

    private void doConnect(String ip, int port, AndroidAutoReceiver.StatusListener status) {
        log(status, "--- Wireless AA manual connect (experimental - OpenAuto-style, phone as TCP server) ---");
        if (!AndroidAutoReceiver.enabled) {
            log(status, "Wireless manual connect: master toggle is OFF - enable the AA receiver "
                    + "toggle first (off by default, same switch every other AA path uses).");
            return;
        }
        if (ip == null || ip.trim().isEmpty()) {
            log(status, "No IP address entered. Enable \"Start head unit server\" in the phone's "
                    + "Android Auto developer settings first - it will show the IP to use here.");
            return;
        }
        setStage(status, AndroidAutoReceiver.StageStatus.RUNNING);
        log(status, "Connecting to " + ip + ":" + port + "...");
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip.trim(), port), 8000);
            log(status, "Connected: " + socket.getRemoteSocketAddress() + " - plain TCP session open. "
                    + "(Not yet wired into the VersionRequest/VersionResponse exchange - that's the "
                    + "next increment, same as the Bluetooth bootstrap path; closing this test "
                    + "connection now.)");
            setStage(status, AndroidAutoReceiver.StageStatus.TESTED_WORKING);
        } catch (IOException e) {
            log(status, "Connection failed: " + e);
            setStage(status, AndroidAutoReceiver.StageStatus.TESTED_FAILING);
        } finally {
            closeSocket();
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }
}
