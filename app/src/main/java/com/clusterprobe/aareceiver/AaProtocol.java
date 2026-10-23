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
     *  Switched reads to that (see readOnce/readMessage below). That alone wasn't the whole
     *  answer though: the next on-car run showed requestWait() genuinely completing (no more hard
     *  timeout) but with 0 bytes, on two separate fresh runs - see readMessage's own doc comment
     *  for the single-larger-buffer-plus-retry change made in response to that. */
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

    /** Generous upper bound for a single read - comfortably larger than any message this class
     *  currently sends or expects (VersionResponse is a handful of bytes). Reading everything in
     *  one shot, rather than a precise 4-byte header read followed by a precise payload read,
     *  deliberately avoids a real pitfall: if the phone sends its whole reply as ONE USB
     *  transfer, asking for a smaller piece first can interact badly with some USB controllers -
     *  a 2026-10-23 on-car test showed the header-only read completing with 0 bytes on two
     *  separate fresh runs, which this single-larger-buffer approach is meant to rule out (or
     *  confirm isn't the cause, if it still returns 0). */
    private static final int READ_BUFFER_SIZE = 512;
    private static final int READ_RETRY_ATTEMPTS = 3;
    private static final long READ_RETRY_DELAY_MS = 150;

    /** Reads exactly one single-frame (BULK) message. Fails cleanly (not a crash) if a
     *  MIDDLE/FIRST/LAST multi-frame sequence is seen, since reassembly isn't implemented. */
    public static Result readMessage(UsbDeviceConnection connection, UsbEndpoint bulkIn) {
        byte[] data = new byte[READ_BUFFER_SIZE];
        int length;
        int attempt = 0;
        while (true) {
            attempt++;
            ChunkResult cr = readOnce(connection, bulkIn, data);
            if (cr.hardFailure != null) return Result.fail(cr.hardFailure);
            if (cr.length > 0) {
                length = cr.length;
                break;
            }
            // Zero-byte completion (not a timeout - a genuine completed request with nothing in
            // it). Could be a spurious/premature empty completion rather than a real "nothing to
            // read" - retry a few times with a short delay before giving up.
            if (attempt >= READ_RETRY_ATTEMPTS) {
                return Result.fail("received a 0-byte completion on all " + READ_RETRY_ATTEMPTS
                        + " attempt(s) - the phone does not appear to be sending a response at "
                        + "all, not just a framing/chunking problem on our side.");
            }
            try {
                Thread.sleep(READ_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                return Result.fail("interrupted while retrying after a 0-byte completion.");
            }
        }

        if (length < 4) {
            return Result.fail("received only " + length + " byte(s) - too small to even hold a "
                    + "2-byte frame header + 2-byte size (need >=4).");
        }

        int channelId = data[0] & 0xFF;
        int flags = data[1] & 0xFF;
        int frameType = flags & FRAME_TYPE_BULK; // FIRST|LAST bits
        boolean encrypted = (flags & ENCRYPTION_ENCRYPTED) != 0;
        int shortSize = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

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
        if (4 + shortSize > length) {
            return Result.fail("frame declares a " + shortSize + "-byte payload, but this read "
                    + "only captured " + (length - 4) + " payload byte(s) (" + length + " total) - "
                    + "the response may have arrived across more than one USB transfer, which "
                    + "isn't handled here yet.");
        }

        int messageId = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        byte[] body = new byte[shortSize - 2];
        System.arraycopy(data, 6, body, 0, body.length);

        AaMessage msg = new AaMessage(channelId, messageId, body);
        return Result.okMessage(msg, "channel=" + channelId + " messageId=0x"
                + Integer.toHexString(messageId) + " " + (encrypted ? "ENCRYPTED" : "PLAIN")
                + " body=" + body.length + " bytes (read " + length + " bytes total on attempt "
                + attempt + ").");
    }

    private static final class ChunkResult {
        final int length; // 0 if nothing was received (but not a hard failure)
        final String hardFailure; // non-null only for a real error/timeout, not a 0-byte completion
        ChunkResult(int length, String hardFailure) { this.length = length; this.hardFailure = hardFailure; }
    }

    /** One UsbRequest/requestWait cycle into `out`. Returns length=0 (not a failure) for a
     *  genuine zero-byte completion, so the caller can decide whether to retry. */
    private static ChunkResult readOnce(UsbDeviceConnection connection, UsbEndpoint bulkIn, byte[] out) {
        UsbRequest request = new UsbRequest();
        ByteBuffer buffer = ByteBuffer.allocate(out.length);
        try {
            if (!request.initialize(connection, bulkIn)) {
                return new ChunkResult(0, "UsbRequest.initialize() returned false.");
            }
            if (!request.queue(buffer)) {
                return new ChunkResult(0, "UsbRequest.queue() returned false.");
            }
            UsbRequest completed;
            try {
                completed = connection.requestWait(READ_TIMEOUT_MS);
            } catch (Throwable t) {
                return new ChunkResult(0, "requestWait() threw: " + t);
            }
            if (completed == null) {
                return new ChunkResult(0, "requestWait() timed out after " + READ_TIMEOUT_MS + "ms.");
            }
            int n = buffer.position();
            if (n <= 0) {
                return new ChunkResult(0, null); // genuine 0-byte completion, not a hard failure
            }
            buffer.flip();
            buffer.get(out, 0, n);
            return new ChunkResult(n, null);
        } catch (Throwable t) {
            return new ChunkResult(0, "readOnce threw: " + t);
        } finally {
            try { request.close(); } catch (Throwable ignored) { }
        }
    }

    private static String frameTypeName(int frameType) {
        switch (frameType) {
            case FRAME_TYPE_MIDDLE: return "MIDDLE";
            case FRAME_TYPE_FIRST: return "FIRST";
            case FRAME_TYPE_LAST: return "LAST";
            default: return "unknown(" + frameType + ")";
        }
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
