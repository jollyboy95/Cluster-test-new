package com.clusterprobe.aareceiver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import java.nio.charset.StandardCharsets;

/**
 * The official Google AOAP (Android Open Accessory Protocol) handshake, implemented directly
 * against Android's own public UsbManager/UsbDeviceConnection API - no native code, no libusb,
 * nothing vendored from aasdk here (aasdk's own USB layer assumes direct libusb device access,
 * which a normal Android app doesn't have - see docs/H7_DISPLAY_ARCHITECTURE.md). This is the
 * same handshake every USB Android Auto head unit performs; it is publicly documented by Google
 * (developer.android.com/develop/connectivity/usb/accessory) and reimplemented independently
 * here against that public spec, not copied from anyone else's code.
 *
 * Sequence, each step a plain USB control transfer on endpoint 0:
 *  1. GetProtocol (0x33, device-to-host) - ask the phone whether/which AOAP version it supports.
 *  2. SendString (0x34, host-to-device) x6 - identify this "accessory" (us) to the phone.
 *  3. StartAccessory (0x35, host-to-device) - tell the phone to switch into accessory mode.
 *
 * After step 3 the phone disconnects and re-enumerates as a USB accessory with a NEW vendor/
 * product ID (Google's, 0x18D1 / 0x2D00 or 0x2D01) - this class's job ends at "StartAccessory
 * sent successfully". Detecting that re-enumeration and opening the resulting UsbAccessory is
 * ConnectionStage.ACCESSORY_REOPEN, a separate, not-yet-implemented step (see
 * AndroidAutoReceiver).
 */
public final class AoapNegotiator {

    public static final int ACCESSORY_GET_PROTOCOL = 51;
    public static final int ACCESSORY_SEND_STRING = 52;
    public static final int ACCESSORY_START = 53;

    private static final int STRING_ID_MANUFACTURER = 0;
    private static final int STRING_ID_MODEL = 1;
    private static final int STRING_ID_DESCRIPTION = 2;
    private static final int STRING_ID_VERSION = 3;
    private static final int STRING_ID_URI = 4;
    private static final int STRING_ID_SERIAL = 5;

    private static final int TIMEOUT_MS = 2000;

    // Identifying strings this "accessory" (ClusterProbe, acting as a USB Android Auto head
    // unit) presents to the phone during negotiation. "Android"/"Android Auto" as manufacturer/
    // model match what AOAP's own spec examples use for an Android Auto accessory - this is
    // public protocol convention, not anything proprietary to GWM, aasdk, or any other project.
    private static final String MANUFACTURER = "Android";
    private static final String MODEL = "Android Auto";
    private static final String DESCRIPTION = "ClusterProbe Android Auto receiver (experimental)";
    private static final String VERSION = "2.0.1";
    private static final String URI = "https://github.com/jollyboy95/cluster-test-new";
    private static final String SERIAL = "clusterprobe-aareceiver-0001";

    public static final class ProtocolQueryResult {
        public final boolean success;
        public final int protocolVersion; // -1 if unknown/failed
        public final String detail;

        private ProtocolQueryResult(boolean success, int protocolVersion, String detail) {
            this.success = success;
            this.protocolVersion = protocolVersion;
            this.detail = detail;
        }
    }

    public static final class StepResult {
        public final boolean success;
        public final String detail;

        private StepResult(boolean success, String detail) {
            this.success = success;
            this.detail = detail;
        }

        static StepResult ok(String detail) { return new StepResult(true, detail); }
        static StepResult fail(String detail) { return new StepResult(false, detail); }
    }

    private AoapNegotiator() {
    }

    /**
     * Step 1: ACCESSORY_GET_PROTOCOL. A device that answers with a 2-byte little-endian version
     * >= 1 supports AOAP and is a real candidate - every modern Android phone does. This alone
     * does NOT distinguish "an Android phone" from "some other USB device that happens to
     * respond" with full certainty, but a non-Android USB device is extremely unlikely to answer
     * this exact vendor request at all, so a successful response here is a strong, practical
     * signal, not a guess.
     */
    public static ProtocolQueryResult queryProtocol(UsbDeviceConnection connection) {
        byte[] buffer = new byte[2];
        int result;
        try {
            result = connection.controlTransfer(
                    UsbConstants.USB_DIR_IN | UsbConstants.USB_TYPE_VENDOR,
                    ACCESSORY_GET_PROTOCOL,
                    0, 0, buffer, buffer.length, TIMEOUT_MS);
        } catch (Throwable t) {
            return new ProtocolQueryResult(false, -1, "controlTransfer threw: " + t);
        }
        if (result != 2) {
            return new ProtocolQueryResult(false, -1,
                    "controlTransfer returned " + result + " (expected 2 bytes) - device likely "
                            + "doesn't support AOAP, or isn't ready/unlocked.");
        }
        int version = (buffer[0] & 0xFF) | ((buffer[1] & 0xFF) << 8);
        if (version < 1) {
            return new ProtocolQueryResult(false, version,
                    "Device answered but reported protocol version " + version + " (not AOAP-capable).");
        }
        return new ProtocolQueryResult(true, version, "AOAP protocol version " + version + " supported.");
    }

    /**
     * Step 2: ACCESSORY_SEND_STRING x6. Must happen in order; a phone that's genuinely waiting
     * for an accessory handshake accepts all six before StartAccessory is sent.
     */
    public static StepResult sendIdentifyingStrings(UsbDeviceConnection connection) {
        String[] values = {MANUFACTURER, MODEL, DESCRIPTION, VERSION, URI, SERIAL};
        int[] ids = {STRING_ID_MANUFACTURER, STRING_ID_MODEL, STRING_ID_DESCRIPTION,
                STRING_ID_VERSION, STRING_ID_URI, STRING_ID_SERIAL};
        for (int i = 0; i < values.length; i++) {
            StepResult r = sendOneString(connection, ids[i], values[i]);
            if (!r.success) {
                return StepResult.fail("String #" + ids[i] + " (\"" + values[i] + "\") failed: " + r.detail);
            }
        }
        return StepResult.ok("All 6 identifying strings accepted.");
    }

    private static StepResult sendOneString(UsbDeviceConnection connection, int stringId, String value) {
        // AOAP strings are NUL-terminated.
        byte[] data = (value + "\0").getBytes(StandardCharsets.US_ASCII);
        int result;
        try {
            result = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                    ACCESSORY_SEND_STRING,
                    0, stringId, data, data.length, TIMEOUT_MS);
        } catch (Throwable t) {
            return StepResult.fail("controlTransfer threw: " + t);
        }
        if (result != data.length) {
            return StepResult.fail("controlTransfer returned " + result + ", expected " + data.length);
        }
        return StepResult.ok("sent " + data.length + " bytes");
    }

    /**
     * Step 3: ACCESSORY_START. After this returns successfully, the phone is expected to
     * disconnect and re-enumerate in accessory mode - this UsbDeviceConnection becomes useless
     * the moment that happens, which is expected, not an error.
     */
    public static StepResult startAccessory(UsbDeviceConnection connection) {
        int result;
        try {
            result = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT | UsbConstants.USB_TYPE_VENDOR,
                    ACCESSORY_START,
                    0, 0, null, 0, TIMEOUT_MS);
        } catch (Throwable t) {
            return StepResult.fail("controlTransfer threw: " + t);
        }
        if (result < 0) {
            return StepResult.fail("controlTransfer returned " + result);
        }
        return StepResult.ok("StartAccessory sent (result=" + result + ") - phone should now "
                + "disconnect and re-enumerate as a USB accessory (new vendor/product ID, Google's "
                + "0x18D1). ConnectionStage.ACCESSORY_REOPEN is not implemented yet - watch for the "
                + "phone to drop off and come back, but this app does not yet detect or open that "
                + "re-enumerated accessory.");
    }

    /** Brief, human-readable identity of a candidate USB device, for the diagnostic log. */
    public static String describe(UsbDevice device) {
        return device.getDeviceName() + "  vendorId=0x" + Integer.toHexString(device.getVendorId())
                + " productId=0x" + Integer.toHexString(device.getProductId())
                + "  manufacturer=" + safe(device.getManufacturerName())
                + " product=" + safe(device.getProductName());
    }

    private static String safe(String s) {
        return s == null ? "<unknown>" : s;
    }
}
