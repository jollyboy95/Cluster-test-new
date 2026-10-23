package com.clusterprobe.aareceiver;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import java.nio.ByteBuffer;

/**
 * The real Android Auto wire framing, read directly from aasdk's actual source
 * (f1xpl/aasdk, commit 046b3b381595509d0939fa84b14a90978f46ff63, GPLv3) on 2026-10-23 -
 * specifically Messenger/FrameHeader.{hpp,cpp}, Messenger/FrameSize.{hpp,cpp},
 * Messenger/EncryptionType.hpp, Messenger/MessageType.hpp, Messenger/ChannelId.hpp, and
 * Messenger/MessageOutStream.cpp (for exactly when SHORT vs. EXTENDED framing is used). This is
 * the same "use the real spec, not aasdk's C++" approach as the vendored .proto files - nothing
 * here is copied from aasdk's C++, it's reimplemented in Java directly against the same protocol
 * aasdk's own frame classes document.
 *
 * Frame wire format (2-byte header + 2-or-6-byte size + payload):
 *  byte 0: channelId (CONTROL=0, INPUT=1, SENSOR=2, VIDEO=3, MEDIA_AUDIO=4, SPEECH_AUDIO=5,
 *          SYSTEM_AUDIO=6, AV_INPUT=7, BLUETOOTH=8)
 *  byte 1: (frameType) | (encryptionType) | (messageType), bitwise OR:
 *          frameType: MIDDLE=0, FIRST=1, LAST=2, BULK=(FIRST|LAST)=3
 *          encryptionType: PLAIN=0, ENCRYPTED=1<<3=8
 *          messageType: SPECIFIC=0, CONTROL=1<<2=4 (NOTE: confirmed from
 *          ControlServiceChannel.cpp that every real control-channel message - VersionRequest,
 *          SSL_HANDSHAKE, AuthComplete, etc. - uses MessageType.SPECIFIC, not CONTROL, despite
 *          being on channel CONTROL=0. "MessageType.CONTROL" is a different, lower-level thing
 *          this class doesn't use.)
 *  size (SHORT, 2 bytes): big-endian uint16 payload length - used whenever the whole message
 *          fits in one frame (frameType=BULK), which covers every message this class sends/reads
 *          (all well under aasdk's own 0x4000-byte max single-frame payload). EXTENDED framing
 *          (used only for a true multi-frame split, frameType=FIRST) is NOT implemented here -
 *          detected and reported, not silently mishandled, if it's ever seen.
 *  payload: for a "message" (as opposed to a raw frame), the payload's first 2 bytes are a
 *          big-endian message ID (e.g. SSL_HANDSHAKE=0x0003, from ControlMessageIdsEnum.proto,
 *          already vendored), followed by the message body.
 *
 * Reads/writes go directly over the bulk IN/OUT endpoints AoapNegotiator.openAccessoryEndpoints
 * already claimed - no TLS here yet (ConnectionStage.SSL_HANDSHAKE, separate, unimplemented).
 */
public final class AaProtocol {

    public static final int CHANNEL_CONTROL = 0;

    private static final int FRAME_TYPE_MIDDLE = 0;
    private static final int FRAME_TYPE_FIRST = 1;
    private static final int FRAME_TYPE_LAST = 2;
    private static final int FRAME_TYPE_BULK = FRAME_TYPE_FIRST | FRAME_TYPE_LAST;

    private static final int ENCRYPTION_PLAIN = 0;
    private static final int ENCRYPTION_ENCRYPTED = 1 << 3;

    private static final int MESSAGE_TYPE_SPECIFIC = 0;

    public static final int MESSAGE_ID_VERSION_REQUEST = 0x0001;
    public static final int MESSAGE_ID_VERSION_RESPONSE = 0x0002;
    public static final int MESSAGE_ID_SSL_HANDSHAKE = 0x0003;

    /** The version this receiver declares - matches aasdk's own declared 1.1, the real,
     *  widely-interoperable value the public spec/community implementations use. */
    public static final int VERSION_MAJOR = 1;
    public static final int VERSION_MINOR = 1;

    private static final int WRITE_TIMEOUT_MS = 3000;
    /** 2026-10-23 on-car result: the VersionRequest WRITE succeeded via plain bulkTransfer(), but
     *  the response READ timed out ("returned -1 after 0/4 bytes") even though the write clearly
     *  reached the phone. Android's synchronous UsbDeviceConnection.bulkTransfer() for READS has
     *  well-documented reliability problems on several SoCs, Qualcomm platforms (this head unit's
     *  SA8155) among them - community Android-Auto-host implementations generally use the
     *  asynchronous UsbRequest/requestWait() API for bulk reads specifically because of this.
     *  Switched readExactly to that below. Not a certain fix - genuinely don't know yet whether
     *  this was the real cause or something else (e.g. the phone just needing longer before its
     *  first response) - this is the most plausible, well-precedented explanation to try first. */
    private static final int READ_TIMEOUT_MS = 6000;

    public static final class AaMessage {
        public final int channelId;
        public final int messageId;
        public final byte[] body; // payload with the 2-byte message ID already stripped off

        AaMessage(int channelId, int messageId, byte[] body) {
            this.channelId = channelId;
            this.messageId = messageId;
            this.body = body;
        }
    }

    public static final class Result {
        public final boolean success;
        public final String detail;
        public final AaMessage message; // non-null only for a successful readMessage

        private Result(boolean success, String detail, AaMessage message) {
            this.success = success;
            this.detail = detail;
            this.message = message;
        }

        static Result ok(String detail) { return new Result(true, detail, null); }
        static Result okMessage(AaMessage m, String detail) { return new Result(true, detail, m); }
        static Result fail(String detail) { return new Result(false, detail, null); }
    }

    private AaProtocol() {
    }

    /** Builds and sends one single-frame (BULK) message: channelId + flags + SHORT size +
     *  [2-byte messageId][body]. */
    public static Result writeMessage(UsbDeviceConnection connection, UsbEndpoint bulkOut,
                                       int channelId, int messageId, byte[] body, boolean encrypted) {
        byte[] payload = new byte[2 + body.length];
        payload[0] = (byte) ((messageId >> 8) & 0xFF);
        payload[1] = (byte) (messageId & 0xFF);
        System.arraycopy(body, 0, payload, 2, body.length);

        if (payload.length > 0xFFFF) {
            return Result.fail("payload (" + payload.length + " bytes) exceeds what SHORT framing "
                    + "can describe (0xFFFF) - EXTENDED/multi-frame framing isn't implemented here.");
        }

        byte[] frame = new byte[2 + 2 + payload.length];
        frame[0] = (byte) channelId;
        frame[1] = (byte) (FRAME_TYPE_BULK | (encrypted ? ENCRYPTION_ENCRYPTED : ENCRYPTION_PLAIN) | MESSAGE_TYPE_SPECIFIC);
        frame[2] = (byte) ((payload.length >> 8) & 0xFF);
        frame[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, frame, 4, payload.length);

        int sent;
        try {
            sent = connection.bulkTransfer(bulkOut, frame, frame.length, WRITE_TIMEOUT_MS);
        } catch (Throwable t) {
            return Result.fail("bulkTransfer (write) threw: " + t);
        }
        if (sent != frame.length) {
            return Result.fail("bulkTransfer (write) sent " + sent + " of " + frame.length + " bytes.");
        }
        return Result.ok("sent messageId=0x" + Integer.toHexString(messageId) + ", " + frame.length
                + " bytes total (" + body.length + "-byte body).");
    }

    /** Reads exactly one single-frame (BULK) message. Fails cleanly (not a crash) if a
     *  MIDDLE/FIRST/LAST multi-frame sequence is seen, since reassembly isn't implemented. */
    public static Result readMessage(UsbDeviceConnection connection, UsbEndpoint bulkIn) {
        byte[] header = new byte[4]; // 2-byte frame header + 2-byte SHORT size
        Result headerResult = readExactly(connection, bulkIn, header);
        if (!headerResult.success) return headerResult;

        int channelId = header[0] & 0xFF;
        int flags = header[1] & 0xFF;
        int frameType = flags & FRAME_TYPE_BULK; // FIRST|LAST bits
        boolean encrypted = (flags & ENCRYPTION_ENCRYPTED) != 0;
        int shortSize = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);

        if (frameType != FRAME_TYPE_BULK) {
            return Result.fail("received a " + frameTypeName(frameType) + " frame (channel="
                    + channelId + ") - multi-frame reassembly (EXTENDED size, MIDDLE/LAST "
                    + "continuation) isn't implemented here yet. Nothing was lost, but this "
                    + "message can't be read.");
        }
        if (shortSize < 2) {
            return Result.fail("frame declares a " + shortSize + "-byte payload - too small to "
                    + "even hold a 2-byte message ID.");
        }

        byte[] payload = new byte[shortSize];
        Result payloadResult = readExactly(connection, bulkIn, payload);
        if (!payloadResult.success) return payloadResult;

        int messageId = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        byte[] body = new byte[payload.length - 2];
        System.arraycopy(payload, 2, body, 0, body.length);

        AaMessage msg = new AaMessage(channelId, messageId, body);
        return Result.okMessage(msg, "channel=" + channelId + " messageId=0x"
                + Integer.toHexString(messageId) + " " + (encrypted ? "ENCRYPTED" : "PLAIN")
                + " body=" + body.length + " bytes.");
    }

    private static String frameTypeName(int frameType) {
        switch (frameType) {
            case FRAME_TYPE_MIDDLE: return "MIDDLE";
            case FRAME_TYPE_FIRST: return "FIRST";
            case FRAME_TYPE_LAST: return "LAST";
            default: return "unknown(" + frameType + ")";
        }
    }

    /** Loops reads into `out` until it's full or a read fails/times out. Uses the asynchronous
     *  UsbRequest/requestWait() API rather than the synchronous UsbDeviceConnection.bulkTransfer()
     *  used for writes - see the READ_TIMEOUT_MS comment above for why (a 2026-10-23 on-car run
     *  showed a write via bulkTransfer succeeding while the read via bulkTransfer reliably timed
     *  out on this exact hardware, which matches a known class of synchronous-bulk-read
     *  reliability problem on several Android USB host controllers). */
    private static Result readExactly(UsbDeviceConnection connection, UsbEndpoint bulkIn, byte[] out) {
        int got = 0;
        while (got < out.length) {
            int remaining = out.length - got;
            UsbRequest request = new UsbRequest();
            ByteBuffer buffer = ByteBuffer.allocate(remaining);
            try {
                if (!request.initialize(connection, bulkIn)) {
                    return Result.fail("UsbRequest.initialize() returned false after " + got + "/"
                            + out.length + " bytes.");
                }
                if (!request.queue(buffer)) {
                    return Result.fail("UsbRequest.queue() returned false after " + got + "/"
                            + out.length + " bytes.");
                }
                UsbRequest completed;
                try {
                    completed = connection.requestWait(READ_TIMEOUT_MS);
                } catch (Throwable t) {
                    return Result.fail("requestWait() threw after " + got + "/" + out.length
                            + " bytes: " + t);
                }
                if (completed == null) {
                    return Result.fail("requestWait() timed out after " + READ_TIMEOUT_MS
                            + "ms (got " + got + "/" + out.length + " bytes so far).");
                }
                int n = buffer.position(); // bytes actually transferred into the buffer
                if (n <= 0) {
                    return Result.fail("UsbRequest completed with " + n + " bytes after " + got
                            + "/" + out.length + " bytes (nothing sent, or a zero-length packet).");
                }
                buffer.flip();
                buffer.get(out, got, n);
                got += n;
            } catch (Throwable t) {
                return Result.fail("readExactly threw after " + got + "/" + out.length + " bytes: " + t);
            } finally {
                try { request.close(); } catch (Throwable ignored) { }
            }
        }
        return Result.ok("read " + out.length + " bytes (via UsbRequest).");
    }

    /** Builds a VersionRequest body: 4 bytes = major(BE uint16) + minor(BE uint16), per
     *  ControlServiceChannel::sendVersionRequest in the real aasdk source. Not protobuf-encoded -
     *  this specific message is a quirk of the protocol, raw bytes only. */
    public static byte[] buildVersionRequestBody() {
        return new byte[] {
                (byte) ((VERSION_MAJOR >> 8) & 0xFF), (byte) (VERSION_MAJOR & 0xFF),
                (byte) ((VERSION_MINOR >> 8) & 0xFF), (byte) (VERSION_MINOR & 0xFF)
        };
    }

    public static final class VersionResponse {
        public final int major;
        public final int minor;
        public final int status; // 0 = MATCH, 0xFFFF = MISMATCH, per VersionResponseStatusEnum.proto
        VersionResponse(int major, int minor, int status) {
            this.major = major; this.minor = minor; this.status = status;
        }
        public boolean isMatch() { return status == 0; }
    }

    /** Parses a VersionResponse body: same raw-bytes quirk as the request, optionally with a
     *  3rd big-endian uint16 status field (MATCH=0/MISMATCH=0xFFFF) - mirrors
     *  ControlServiceChannel::handleVersionResponse's tolerance of a short body. */
    public static VersionResponse parseVersionResponseBody(byte[] body) {
        int major = body.length >= 2 ? (((body[0] & 0xFF) << 8) | (body[1] & 0xFF)) : 0;
        int minor = body.length >= 4 ? (((body[2] & 0xFF) << 8) | (body[3] & 0xFF)) : 0;
        int status = body.length >= 6 ? (((body[4] & 0xFF) << 8) | (body[5] & 0xFF)) : 0xFFFF;
        return new VersionResponse(major, minor, status);
    }
}
