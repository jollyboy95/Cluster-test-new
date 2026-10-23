package com.clusterprobe.aareceiver;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the Android Auto receiver this project is building - see
 * docs/H7_DISPLAY_ARCHITECTURE.md's "Android Auto receiver" section for the full design and why
 * each piece is built the way it is.
 *
 * EXPLICITLY NOT a working Android Auto receiver yet. Per project rules: "do not claim a working
 * receiver until a real phone connection and video frame have been demonstrated." As of this
 * version, only the USB/AOAP negotiation up through StartAccessory is implemented, and even that
 * is UNTESTED against a real phone until an on-car run confirms it. Everything past
 * ACCESSORY_REOPEN (SSL handshake, auth, channel open, video decode) and the entire wireless
 * bootstrap path are NOT_IMPLEMENTED. getStageStatuses() reports the real, current state of each
 * ConnectionStage for the diagnostic screen - nothing here fabricates progress.
 *
 * Disabled by default and only does anything when explicitly triggered from the diagnostic
 * screen's "Run USB AOAP negotiation (experimental)" button - no manifest-registered
 * USB_DEVICE_ATTACHED receiver, no background listening, nothing that could interfere with the
 * factory GWM Android Auto receiver or the phone's existing pairing unless the user explicitly
 * presses that button while a phone is physically plugged in for this specific test.
 */
public class AndroidAutoReceiver {

    public interface StatusListener {
        void onStatus(String msg);
        void onStageUpdate(ConnectionStage stage, StageStatus status);
    }

    public enum StageStatus {
        NOT_IMPLEMENTED,
        IMPLEMENTED_UNTESTED,
        RUNNING,
        TESTED_WORKING,
        TESTED_FAILING
    }

    /** Master "is this feature allowed to do anything at all" switch - off by default, flipped
     *  only by the diagnostic screen's own toggle. run() refuses to do anything while this is
     *  false, independent of whatever button was pressed, as a second layer of "disabled by
     *  default" beyond just not wiring the button up. */
    public static volatile boolean enabled = false;

    private static final Map<ConnectionStage, StageStatus> stageStatuses = buildInitialStatuses();

    private static Map<ConnectionStage, StageStatus> buildInitialStatuses() {
        Map<ConnectionStage, StageStatus> m = new EnumMap<>(ConnectionStage.class);
        for (ConnectionStage s : ConnectionStage.values()) m.put(s, StageStatus.NOT_IMPLEMENTED);
        return m;
    }

    public static Map<ConnectionStage, StageStatus> getStageStatuses() {
        return new HashMap<>(stageStatuses);
    }

    private static AndroidAutoReceiver instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopRequested = false;

    public static void run(Context context, StatusListener status) {
        stop();
        instance = new AndroidAutoReceiver();
        Context appCtx = context.getApplicationContext();
        Thread t = new Thread(() -> instance.doRun(appCtx, status), "aareceiver-usb-negotiation");
        t.setDaemon(true);
        t.start();
    }

    public static void stop() {
        if (instance != null) instance.stopRequested = true;
        instance = null;
    }

    private void log(StatusListener status, String msg) {
        if (status != null) main.post(() -> status.onStatus(msg));
    }

    private void setStage(StatusListener status, ConnectionStage stage, StageStatus s) {
        stageStatuses.put(stage, s);
        if (status != null) main.post(() -> status.onStageUpdate(stage, s));
    }

    private void doRun(Context context, StatusListener status) {
        log(status, "--- Android Auto receiver: USB/AOAP negotiation (experimental, read/negotiate-only) ---");
        if (!enabled) {
            log(status, "AA receiver: master toggle is OFF - not doing anything. Enable it in the "
                    + "diagnostic screen first (off by default on purpose).");
            return;
        }

        setStage(status, ConnectionStage.USB_DEVICE_ENUMERATION, StageStatus.RUNNING);
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            log(status, "AA receiver: UsbManager not available on this build (getSystemService "
                    + "returned null). Cannot proceed.");
            setStage(status, ConnectionStage.USB_DEVICE_ENUMERATION, StageStatus.TESTED_FAILING);
            return;
        }

        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        log(status, "AA receiver: " + devices.size() + " USB device(s) currently attached.");
        if (devices.isEmpty()) {
            log(status, "AA receiver: nothing attached - plug the phone in via USB and try again. "
                    + "No candidate to negotiate with.");
            setStage(status, ConnectionStage.USB_DEVICE_ENUMERATION, StageStatus.TESTED_FAILING);
            return;
        }
        for (UsbDevice d : devices.values()) {
            log(status, "AA receiver: candidate - " + AoapNegotiator.describe(d));
        }
        setStage(status, ConnectionStage.USB_DEVICE_ENUMERATION, StageStatus.TESTED_WORKING);

        // Try every attached device in turn - we don't know in advance which one (if any) is the
        // phone vs. some other attached USB peripheral, and AOAP's own GetProtocol query is
        // itself the cheapest, safest way to find out (see AoapNegotiator.queryProtocol's doc).
        for (UsbDevice device : devices.values()) {
            if (stopRequested) {
                log(status, "AA receiver: stopped.");
                return;
            }
            log(status, "AA receiver: trying " + AoapNegotiator.describe(device) + " ...");
            if (!negotiateWithDevice(context, usbManager, device, status)) {
                log(status, "AA receiver: " + device.getDeviceName() + " did not complete AOAP "
                        + "negotiation - trying next device, if any.");
                continue;
            }
            // First device to succeed is enough for this diagnostic - stop here rather than also
            // issuing ACCESSORY_START to every other attached device.
            return;
        }
        log(status, "AA receiver: no attached device completed AOAP negotiation.");
    }

    private boolean negotiateWithDevice(Context context, UsbManager usbManager, UsbDevice device, StatusListener status) {
        setStage(status, ConnectionStage.USB_PERMISSION, StageStatus.RUNNING);
        if (!usbManager.hasPermission(device)) {
            boolean granted = requestPermissionAndWait(context, usbManager, device, status);
            if (!granted) {
                log(status, "AA receiver: USB permission not granted for " + device.getDeviceName() + ".");
                setStage(status, ConnectionStage.USB_PERMISSION, StageStatus.TESTED_FAILING);
                return false;
            }
        }
        log(status, "AA receiver: have USB permission for " + device.getDeviceName() + ".");
        setStage(status, ConnectionStage.USB_PERMISSION, StageStatus.TESTED_WORKING);

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            log(status, "AA receiver: openDevice() returned null for " + device.getDeviceName() + ".");
            return false;
        }
        try {
            setStage(status, ConnectionStage.AOAP_PROTOCOL_QUERY, StageStatus.RUNNING);
            AoapNegotiator.ProtocolQueryResult protocolResult = AoapNegotiator.queryProtocol(connection);
            log(status, "AA receiver: AOAP GetProtocol -> " + protocolResult.detail);
            if (!protocolResult.success) {
                setStage(status, ConnectionStage.AOAP_PROTOCOL_QUERY, StageStatus.TESTED_FAILING);
                return false;
            }
            setStage(status, ConnectionStage.AOAP_PROTOCOL_QUERY, StageStatus.TESTED_WORKING);

            setStage(status, ConnectionStage.AOAP_SEND_IDENTIFYING_STRINGS, StageStatus.RUNNING);
            AoapNegotiator.StepResult stringsResult = AoapNegotiator.sendIdentifyingStrings(connection);
            log(status, "AA receiver: AOAP SendString x6 -> " + stringsResult.detail);
            setStage(status, ConnectionStage.AOAP_SEND_IDENTIFYING_STRINGS,
                    stringsResult.success ? StageStatus.TESTED_WORKING : StageStatus.TESTED_FAILING);
            if (!stringsResult.success) return false;

            setStage(status, ConnectionStage.AOAP_START_ACCESSORY, StageStatus.RUNNING);
            AoapNegotiator.StepResult startResult = AoapNegotiator.startAccessory(connection);
            log(status, "AA receiver: AOAP StartAccessory -> " + startResult.detail);
            setStage(status, ConnectionStage.AOAP_START_ACCESSORY,
                    startResult.success ? StageStatus.TESTED_WORKING : StageStatus.TESTED_FAILING);
            if (!startResult.success) return false;

            log(status, "AA receiver: USB/AOAP negotiation as far as this version implements it is "
                    + "DONE. ConnectionStage.ACCESSORY_REOPEN onward (opening the re-enumerated "
                    + "accessory, SSL handshake, auth, video) is NOT_IMPLEMENTED yet - nothing past "
                    + "this point has happened. No video, no connection claim beyond what's logged "
                    + "above.");
            return true;
        } finally {
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean requestPermissionAndWait(Context context, UsbManager usbManager, UsbDevice device, StatusListener status) {
        log(status, "AA receiver: requesting USB permission for " + device.getDeviceName()
                + " - accept the system prompt if one appears.");
        String action = "com.clusterprobe.aareceiver.USB_PERMISSION";
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] grantedHolder = {false};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!action.equals(intent.getAction())) return;
                synchronized (grantedHolder) {
                    grantedHolder[0] = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                }
                latch.countDown();
            }
        };
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(action), flags);
        try {
            context.registerReceiver(receiver, new IntentFilter(action));
        } catch (Throwable t) {
            log(status, "AA receiver: registerReceiver for permission result failed: " + t);
            return false;
        }
        try {
            usbManager.requestPermission(device, pi);
            boolean waited = latch.await(30, TimeUnit.SECONDS);
            if (!waited) {
                log(status, "AA receiver: timed out after 30s waiting for the USB permission prompt "
                        + "to be answered.");
                return false;
            }
            synchronized (grantedHolder) {
                return grantedHolder[0];
            }
        } catch (InterruptedException ie) {
            return false;
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
        }
    }
}
