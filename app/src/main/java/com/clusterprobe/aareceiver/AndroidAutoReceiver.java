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
        // v0.28: on-car logs (2026-10-23) showed this phone stays in Google's accessory mode
        // (vendorId=0x18D1) across separate runs of this app rather than reverting - so a device
        // already in that state must skip the handshake entirely and go straight to opening it.
        // Re-sending GetProtocol/SendString/StartAccessory to an already-accessory device isn't
        // meaningful (those control transfers have no defined behaviour on that interface) even
        // though it happened to return success harmlessly in those runs.
        if (AoapNegotiator.isAccessoryDevice(device)) {
            log(status, "AA receiver: " + AoapNegotiator.describe(device) + " is ALREADY in "
                    + "accessory mode (vendorId=0x18d1) - skipping the GetProtocol/SendString/"
                    + "StartAccessory handshake (not meaningful on a device already past it) and "
                    + "going straight to ConnectionStage.ACCESSORY_REOPEN.");
            return openAccessoryStage(context, usbManager, device, status);
        }

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
        boolean startedAccessory = false;
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
            startedAccessory = true;
            return true; // ACCESSORY_REOPEN (the wait-for-re-enumeration below) happens after
                          // this connection is closed, since the old device object is about to
                          // become invalid the moment the phone actually disconnects.
        } finally {
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
            if (startedAccessory) {
                waitForReenumerationAndOpen(context, usbManager, status);
            }
        }
    }

    /**
     * After a successful StartAccessory, the phone disconnects and re-enumerates with a new
     * vendor/product ID - the old UsbDevice/UsbDeviceConnection are both dead the moment that
     * happens. Listens for UsbManager.ACTION_USB_DEVICE_ATTACHED for a short window and opens
     * whichever newly-attached device looks like an AOAP accessory. If nothing shows up in time,
     * says so plainly rather than guessing - on this car, pressing "Run" again has reliably found
     * the phone already in accessory mode (see the isAccessoryDevice branch above), so that
     * remains a real fallback even when this automatic wait doesn't catch it.
     */
    private boolean waitForReenumerationAndOpen(Context context, UsbManager usbManager, StatusListener status) {
        setStage(status, ConnectionStage.ACCESSORY_REOPEN, StageStatus.RUNNING);
        log(status, "AA receiver: waiting up to 8s for the phone to re-enumerate in accessory mode...");
        String action = UsbManager.ACTION_USB_DEVICE_ATTACHED;
        CountDownLatch latch = new CountDownLatch(1);
        UsbDevice[] foundHolder = {null};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!action.equals(intent.getAction())) return;
                UsbDevice attached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (attached != null && AoapNegotiator.isAccessoryDevice(attached)) {
                    foundHolder[0] = attached;
                    latch.countDown();
                }
            }
        };
        boolean registered = true;
        try {
            context.registerReceiver(receiver, new IntentFilter(action));
        } catch (Throwable t) {
            log(status, "AA receiver: registerReceiver for " + action + " failed: " + t);
            registered = false;
        }
        UsbDevice found = null;
        try {
            if (registered) {
                latch.await(8, TimeUnit.SECONDS);
                found = foundHolder[0];
            }
        } catch (InterruptedException ie) {
            // fall through - found stays null
        } finally {
            if (registered) {
                try { context.unregisterReceiver(receiver); } catch (Throwable ignored) { }
            }
        }
        if (found == null) {
            // Didn't catch the attach broadcast in time - but on this car the device has
            // reliably still been sitting there in accessory mode on the next manual check, so
            // do one direct poll of the current device list before giving up.
            for (UsbDevice d : usbManager.getDeviceList().values()) {
                if (AoapNegotiator.isAccessoryDevice(d)) {
                    found = d;
                    break;
                }
            }
        }
        if (found == null) {
            log(status, "AA receiver: no accessory-mode device showed up within 8s. The phone may "
                    + "still be re-enumerating - press \"Run USB AOAP negotiation\" again in a "
                    + "moment; on this car it has reliably shown up as already-in-accessory-mode "
                    + "on the next run even when this automatic wait misses it.");
            setStage(status, ConnectionStage.ACCESSORY_REOPEN, StageStatus.TESTED_FAILING);
            return false;
        }
        log(status, "AA receiver: re-enumerated as " + AoapNegotiator.describe(found) + ".");
        return openAccessoryStage(context, usbManager, found, status);
    }

    /** Shared "open the already-accessory-mode device" logic - claim its interface, find bulk
     *  IN/OUT endpoints. Reads/writes nothing on those endpoints; that starts at SSL_HANDSHAKE,
     *  not implemented here. Does NOT close the connection on success - a later stage (not yet
     *  implemented) needs it to stay open; closes it on failure since nothing further can happen. */
    private boolean openAccessoryStage(Context context, UsbManager usbManager, UsbDevice device, StatusListener status) {
        setStage(status, ConnectionStage.ACCESSORY_REOPEN, StageStatus.RUNNING);
        if (!usbManager.hasPermission(device)) {
            boolean granted = requestPermissionAndWait(context, usbManager, device, status);
            if (!granted) {
                log(status, "AA receiver: USB permission not granted for the re-enumerated accessory "
                        + "device (" + device.getDeviceName() + ").");
                setStage(status, ConnectionStage.ACCESSORY_REOPEN, StageStatus.TESTED_FAILING);
                return false;
            }
        }
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            log(status, "AA receiver: openDevice() returned null for the accessory-mode device.");
            setStage(status, ConnectionStage.ACCESSORY_REOPEN, StageStatus.TESTED_FAILING);
            return false;
        }
        AoapNegotiator.AccessoryEndpoints endpoints = AoapNegotiator.openAccessoryEndpoints(device, connection);
        log(status, "AA receiver: open accessory interface -> " + endpoints.detail);
        if (!endpoints.success) {
            setStage(status, ConnectionStage.ACCESSORY_REOPEN, StageStatus.TESTED_FAILING);
            try { connection.close(); } catch (Throwable ignored) { }
            return false;
        }
        setStage(status, ConnectionStage.ACCESSORY_REOPEN, StageStatus.TESTED_WORKING);
        log(status, "AA receiver: ConnectionStage.ACCESSORY_REOPEN is DONE - bulk endpoints found "
                + "and interface claimed.");

        return runVersionExchange(connection, endpoints, status);
    }

    /**
     * The real protocol's first message, plaintext, before any TLS: send VersionRequest (major/
     * minor, raw 4 bytes, not protobuf - see AaProtocol), read back VersionResponse. This is a
     * genuine, independently meaningful on-car test of the new AaProtocol frame/message layer,
     * separate from and lower-risk than the actual SSL handshake that comes after it.
     */
    private boolean runVersionExchange(UsbDeviceConnection connection,
                                        AoapNegotiator.AccessoryEndpoints endpoints, StatusListener status) {
        setStage(status, ConnectionStage.VERSION_EXCHANGE, StageStatus.RUNNING);

        AaProtocol.Result sendResult = AaProtocol.writeMessage(connection, endpoints.bulkOut,
                AaProtocol.CHANNEL_CONTROL, AaProtocol.MESSAGE_ID_VERSION_REQUEST,
                AaProtocol.buildVersionRequestBody(), false);
        log(status, "AA receiver: VersionRequest (v" + AaProtocol.VERSION_MAJOR + "."
                + AaProtocol.VERSION_MINOR + ") -> " + sendResult.detail);
        if (!sendResult.success) {
            setStage(status, ConnectionStage.VERSION_EXCHANGE, StageStatus.TESTED_FAILING);
            log(status, "AA receiver: ConnectionStage.SSL_HANDSHAKE onward is NOT_IMPLEMENTED - "
                    + "stopping here. No session, no video.");
            return false;
        }

        AaProtocol.Result readResult = AaProtocol.readMessage(connection, endpoints.bulkIn);
        log(status, "AA receiver: reading VersionResponse -> " + readResult.detail);
        if (!readResult.success) {
            setStage(status, ConnectionStage.VERSION_EXCHANGE, StageStatus.TESTED_FAILING);
            log(status, "AA receiver: ConnectionStage.SSL_HANDSHAKE onward is NOT_IMPLEMENTED - "
                    + "stopping here. No session, no video.");
            return false;
        }

        AaProtocol.AaMessage msg = readResult.message;
        if (msg.messageId != AaProtocol.MESSAGE_ID_VERSION_RESPONSE) {
            log(status, "AA receiver: expected VersionResponse (messageId=0x0002) but got "
                    + "messageId=0x" + Integer.toHexString(msg.messageId) + " instead - unexpected, "
                    + "not handled.");
            setStage(status, ConnectionStage.VERSION_EXCHANGE, StageStatus.TESTED_FAILING);
            log(status, "AA receiver: ConnectionStage.SSL_HANDSHAKE onward is NOT_IMPLEMENTED - "
                    + "stopping here. No session, no video.");
            return false;
        }

        AaProtocol.VersionResponse version = AaProtocol.parseVersionResponseBody(msg.body);
        log(status, "AA receiver: VersionResponse -> phone reports v" + version.major + "."
                + version.minor + ", status=" + (version.isMatch() ? "MATCH" : "MISMATCH (0x"
                + Integer.toHexString(version.status) + ")") + ".");
        setStage(status, ConnectionStage.VERSION_EXCHANGE, StageStatus.TESTED_WORKING);

        log(status, "AA receiver: ConnectionStage.VERSION_EXCHANGE is DONE. ConnectionStage."
                + "SSL_HANDSHAKE onward (the actual TLS handshake, auth, video) is NOT_IMPLEMENTED "
                + "yet - nothing past this point has happened. No session, no video, no connection "
                + "claim beyond what's logged above. Leaving the interface claimed/connection open "
                + "for now (nothing currently closes it) since SSL_HANDSHAKE will need it.");
        return true;
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
