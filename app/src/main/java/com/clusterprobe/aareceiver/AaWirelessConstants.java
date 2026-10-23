package com.clusterprobe.aareceiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Constants and raw framing for the Android-Auto-Wireless Bluetooth RFCOMM "bootstrap" channel -
 * read directly from aa-proxy-rs's actual source (aa-proxy/aa-proxy-rs, commit
 * 5a6887d427f4b6f6395bf1d3d7d4d31f805e4f98, GPLv2) on 2026-10-23, specifically src/bluetooth.rs's
 * AAWG_PROFILE_UUID constant, its MessageId/ProxyMessageId enums, and its send_message()/
 * read_message() functions (the actual head-unit-role implementation, in send_params()).
 *
 * This is a DIFFERENT, simpler wire format from AaProtocol's Messenger framing - it only exists on
 * this one Bluetooth RFCOMM channel, before any Wi-Fi/TCP session exists. Once the phone opens the
 * TCP connection this bootstrap hands it (see AaWirelessBootstrapServer), AaProtocol's framing
 * takes over unchanged - same VersionRequest/VersionResponse/SSL_HANDSHAKE messages already built
 * and confirmed over USB, just over a Socket's streams instead of USB bulk endpoints.
 *
 * Bootstrap frame = 4-byte header (2-byte big-endian payload length + 2-byte big-endian message
 * ID) + payload. Confirmed real sequence (head-unit role, from aa-proxy-rs's send_params()):
 *   1. HU -> phone: AaWirelessStartRequest { ip_address, port }  (where the phone should open the
 *      real AA TCP session once it has joined our Wi-Fi AP)
 *   2. phone -> HU: WifiInfoRequest   (empty body - read_message() reads the header, discards 0
 *      payload bytes; not worth a generated class for an empty message)
 *   3. HU -> phone: AaWirelessInfoResponse { ssid, key, bssid, security_mode=WPA2_PERSONAL,
 *      access_point_type=DYNAMIC }  (our hosted AP's own credentials)
 *   4. phone -> HU: WifiStartResponse  (body has a status field per aa-proxy-rs's own comment -
 *      "WifiStartResponse.status is field #3" - but aa-proxy-rs's real code doesn't parse it, just
 *      discards the bytes by length, so this receiver does the same rather than guessing at an
 *      undocumented field layout)
 *   5. phone -> HU: WifiConnectStatus  (2+ byte protobuf-encoded body; aa-proxy-rs's own code
 *      checks only buf[1] != 0 to detect failure - "[0x08, 0x00]" observed for success, a longer
 *      encoding for failure. Reproduced here exactly as aa-proxy-rs checks it, not as a generated
 *      class, since that's the real, confirmed behavior, not a guess.)
 * After step 5 succeeds, the phone is expected to open a plain TCP connection to the ip:port given
 * in step 1, over the Wi-Fi network it just joined.
 */
public final class AaWirelessConstants {

    /** The real, fixed Android-Auto-Wireless RFCOMM service UUID - every phone's AA app looks for
     *  exactly this UUID's SDP record when attempting a wireless connection to a head unit it has
     *  already Bluetooth-paired with. Not head-unit-specific, not guessed - read directly from
     *  aa-proxy-rs's AAWG_PROFILE_UUID constant. */
    public static final UUID AAWG_PROFILE_UUID =
            UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");

    public static final String RFCOMM_SERVICE_NAME = "AndroidAutoWirelessGateway";

    public static final int MESSAGE_ID_WIFI_START_REQUEST = 1;
    public static final int MESSAGE_ID_WIFI_INFO_REQUEST = 2;
    public static final int MESSAGE_ID_WIFI_INFO_RESPONSE = 3;
    public static final int MESSAGE_ID_WIFI_VERSION_REQUEST = 4;
    public static final int MESSAGE_ID_WIFI_VERSION_RESPONSE = 5;
    public static final int MESSAGE_ID_WIFI_CONNECT_STATUS = 6;
    public static final int MESSAGE_ID_WIFI_START_RESPONSE = 7;
    public static final int MESSAGE_ID_WIFI_PING_REQUEST = 8;
    public static final int MESSAGE_ID_WIFI_PING_RESPONSE = 9;
    public static final int MESSAGE_ID_WIFI_SETUP_INFO = 11;

    private static final int HEADER_LEN = 4;

    public static String messageName(int id) {
        switch (id) {
            case MESSAGE_ID_WIFI_START_REQUEST: return "WifiStartRequest";
            case MESSAGE_ID_WIFI_INFO_REQUEST: return "WifiInfoRequest";
            case MESSAGE_ID_WIFI_INFO_RESPONSE: return "WifiInfoResponse";
            case MESSAGE_ID_WIFI_VERSION_REQUEST: return "WifiVersionRequest";
            case MESSAGE_ID_WIFI_VERSION_RESPONSE: return "WifiVersionResponse";
            case MESSAGE_ID_WIFI_CONNECT_STATUS: return "WifiConnectStatus";
            case MESSAGE_ID_WIFI_START_RESPONSE: return "WifiStartResponse";
            case MESSAGE_ID_WIFI_PING_REQUEST: return "WifiPingRequest";
            case MESSAGE_ID_WIFI_PING_RESPONSE: return "WifiPingResponse";
            case MESSAGE_ID_WIFI_SETUP_INFO: return "WifiSetupInfo";
            default: return "Unknown(" + id + ")";
        }
    }

    public static final class BootstrapMessage {
        public final int messageId;
        public final byte[] body;

        public BootstrapMessage(int messageId, byte[] body) {
            this.messageId = messageId;
            this.body = body;
        }
    }

    /** Writes one bootstrap frame: 2-byte BE length + 2-byte BE messageId + body. */
    public static void writeFrame(OutputStream out, int messageId, byte[] body) throws IOException {
        int len = body == null ? 0 : body.length;
        byte[] header = new byte[HEADER_LEN];
        header[0] = (byte) ((len >> 8) & 0xFF);
        header[1] = (byte) (len & 0xFF);
        header[2] = (byte) ((messageId >> 8) & 0xFF);
        header[3] = (byte) (messageId & 0xFF);
        out.write(header);
        if (len > 0) {
            out.write(body);
        }
        out.flush();
    }

    /** Reads one bootstrap frame. Blocks until the full header + body have arrived or the stream
     *  errors/closes - BluetoothSocket's streams don't need the USB-style "0-byte completion"
     *  workaround AaProtocol needed (that was a USB bulk-transfer-specific quirk on this SoC, not
     *  a general Android I/O issue), so a plain read loop is enough here. */
    public static BootstrapMessage readFrame(InputStream in) throws IOException {
        byte[] header = readExactly(in, HEADER_LEN);
        int len = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int messageId = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] body = len > 0 ? readExactly(in, len) : new byte[0];
        return new BootstrapMessage(messageId, body);
    }

    private static byte[] readExactly(InputStream in, int count) throws IOException {
        byte[] buf = new byte[count];
        int off = 0;
        while (off < count) {
            int n = in.read(buf, off, count - off);
            if (n < 0) {
                throw new IOException("stream closed after " + off + "/" + count + " bytes");
            }
            off += n;
        }
        return buf;
    }

    /** Matches aa-proxy-rs's own WifiConnectStatus check exactly (its read_message() comment: a
     *  success body is observed as [0x08, 0x00], a failure body as a longer protobuf-varint
     *  encoding of a large negative int64 - it only checks buf[1] != 0, so this does the same
     *  rather than guessing at a fuller field layout that was never actually documented upstream
     *  either. */
    public static boolean isWifiConnectStatusSuccess(byte[] body) {
        return body != null && body.length >= 2 && body[1] == 0;
    }

    private AaWirelessConstants() {}
}
