package com.clusterprobe.aareceiver;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Runs a full Android Auto session over an already-connected Socket, as far as this project has
 * gone: version exchange -> TLS handshake -> AuthComplete -> ServiceDiscoveryRequest/Response ->
 * video ChannelOpenRequest/Response -> AVChannelSetupRequest/Response -> START_INDICATION ->
 * raw H.264 frames handed to a VideoListener. This is the SAME protocol layer regardless of
 * transport (USB or any of the wireless paths) - deliberately built transport-agnostic from the
 * start (see AaProtocol's Socket-based overloads) for exactly this reason: aasdk itself shares one
 * protocol layer between its USBTransport and TCPTransport.
 *
 * Message role/direction (who sends what) confirmed by reading aasdk's actual
 * ControlServiceChannel.cpp - see AaControlMessages's doc comment. Video channel id = 3
 * (ChannelId.VIDEO, per AaProtocol's own frame-header doc comment - the fixed, protocol-level
 * channel numbering, not something this project invents).
 *
 * Stops at "H.264 bytes extracted and handed to the listener" - decoding/rendering is the
 * listener's job (AndroidAutoSessionActivity), kept separate so this class stays UI-free and
 * testable on its own.
 */
public final class AaSessionRunner {

    public static final int VIDEO_CHANNEL_ID = 3;

    public interface Listener {
        void onStatus(String msg);
        /** h264 is the raw Annex-B bytes for one AV media indication (SPS/PPS/IDR/slice NALs as
         *  the phone sent them) - not yet demuxed/validated beyond stripping the optional 8-byte
         *  timestamp prefix. */
        void onVideoData(byte[] h264);
        void onSessionEnded(String reason);
    }

    private volatile boolean stopRequested = false;

    public void run(Socket socket, Listener listener) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            listener.onStatus("Session: sending VersionRequest...");
            AaProtocol.writeFrame(out, AaProtocol.CHANNEL_CONTROL, AaProtocol.MESSAGE_ID_VERSION_REQUEST,
                    AaProtocol.buildVersionRequestBody(), false);
            AaProtocol.SocketMessage versionResp = AaProtocol.readFrame(in);
            if (versionResp.messageId != AaProtocol.MESSAGE_ID_VERSION_RESPONSE) {
                listener.onSessionEnded("expected VersionResponse, got messageId=0x"
                        + Integer.toHexString(versionResp.messageId));
                return;
            }
            AaProtocol.VersionResponse vr = AaProtocol.parseVersionResponseBody(versionResp.body);
            listener.onStatus("Session: VersionResponse " + vr.major + "." + vr.minor
                    + (vr.isMatch() ? " (MATCH)" : " (status=0x" + Integer.toHexString(vr.status) + ")"));

            listener.onStatus("Session: starting TLS handshake...");
            AaTlsSession tls = AaTlsSession.create();
            tls.runHandshake(out, in);
            listener.onStatus("Session: TLS handshake complete.");

            listener.onStatus("Session: sending AuthCompleteIndication...");
            AaProtocol.writeFrame(out, AaProtocol.CHANNEL_CONTROL, 0x0004 /* AUTH_COMPLETE */,
                    AaControlMessages.buildAuthCompleteIndication(AaControlMessages.STATUS_OK), false);

            listener.onStatus("Session: waiting for ServiceDiscoveryRequest...");
            AaProtocol.SocketMessage sdrMsg = readControlMessage(in, tls);
            if (sdrMsg.messageId != 0x0005 /* SERVICE_DISCOVERY_REQUEST */) {
                listener.onSessionEnded("expected ServiceDiscoveryRequest, got messageId=0x"
                        + Integer.toHexString(sdrMsg.messageId));
                return;
            }
            AaControlMessages.ServiceDiscoveryRequest sdr =
                    AaControlMessages.parseServiceDiscoveryRequest(decryptIfNeeded(sdrMsg, tls));
            listener.onStatus("Session: ServiceDiscoveryRequest from \"" + sdr.deviceName + "\" ("
                    + sdr.deviceBrand + ")");

            listener.onStatus("Session: sending ServiceDiscoveryResponse (advertising one video channel)...");
            byte[] sdResp = AaControlMessages.buildServiceDiscoveryResponse(VIDEO_CHANNEL_ID,
                    AaControlMessages.VIDEO_RESOLUTION_480P, AaControlMessages.VIDEO_FPS_30, 140);
            AaProtocol.writeFrame(out, AaProtocol.CHANNEL_CONTROL, 0x0006 /* SERVICE_DISCOVERY_RESPONSE */,
                    tls.encrypt(sdResp), true);

            listener.onStatus("Session: waiting for the phone to open the video channel...");
            runReceiveLoop(in, out, tls, listener);

        } catch (Exception e) {
            listener.onSessionEnded("Session failed: " + e);
        }
    }

    public void stop() {
        stopRequested = true;
    }

    private void runReceiveLoop(InputStream in, OutputStream out, AaTlsSession tls, Listener listener)
            throws Exception {
        boolean videoChannelOpen = false;
        boolean videoSetupDone = false;
        long[] ackCounter = {0}; // long[1] so the AV_MEDIA_* cases below can mutate it

        while (!stopRequested) {
            AaProtocol.SocketMessage msg = AaProtocol.readFrame(in);
            byte[] body = decryptIfNeeded(msg, tls);

            if (msg.channelId == AaProtocol.CHANNEL_CONTROL) {
                if (msg.messageId == 0x0007 /* CHANNEL_OPEN_REQUEST */) {
                    AaControlMessages.ChannelOpenRequest req = AaControlMessages.parseChannelOpenRequest(body);
                    listener.onStatus("Session: ChannelOpenRequest for channel " + req.channelId
                            + " (priority=" + req.priority + ")");
                    boolean ok = req.channelId == VIDEO_CHANNEL_ID;
                    AaProtocol.writeFrame(out, AaProtocol.CHANNEL_CONTROL, 0x0008 /* CHANNEL_OPEN_RESPONSE */,
                            tls.encrypt(AaControlMessages.buildChannelOpenResponse(
                                    ok ? AaControlMessages.STATUS_OK : AaControlMessages.STATUS_FAIL)), true);
                    if (ok) {
                        videoChannelOpen = true;
                        listener.onStatus("Session: video channel opened.");
                    }
                } else {
                    listener.onStatus("Session: unhandled CONTROL message id=0x"
                            + Integer.toHexString(msg.messageId) + " (" + body.length + " bytes)");
                }
                continue;
            }

            if (msg.channelId == VIDEO_CHANNEL_ID) {
                switch (msg.messageId) {
                    case 0x8000: { // SETUP_REQUEST
                        int configIndex = AaControlMessages.parseAvChannelSetupRequestConfigIndex(body);
                        listener.onStatus("Session: AVChannelSetupRequest configIndex=" + configIndex);
                        AaProtocol.writeFrame(out, VIDEO_CHANNEL_ID, 0x8003 /* SETUP_RESPONSE */,
                                tls.encrypt(AaControlMessages.buildAvChannelSetupResponse(
                                        AaControlMessages.AV_SETUP_STATUS_OK, 1, configIndex)), true);
                        videoSetupDone = true;
                        listener.onStatus("Session: video channel set up - waiting for START_INDICATION...");
                        break;
                    }
                    case 0x8001: // START_INDICATION
                        listener.onStatus("Session: video START_INDICATION received - phone should "
                                + "start streaming now. THIS IS THE FURTHEST POINT EVER REACHED in "
                                + "this project - next bytes in should be real H.264 video data.");
                        break;
                    case 0x8002: // STOP_INDICATION
                        listener.onStatus("Session: video STOP_INDICATION received.");
                        break;
                    case 0x0000: { // AV_MEDIA_WITH_TIMESTAMP_INDICATION
                        byte[] h264 = body.length > 8
                                ? java.util.Arrays.copyOfRange(body, 8, body.length) : new byte[0];
                        listener.onVideoData(h264);
                        sendMediaAck(out, tls, ackCounter);
                        break;
                    }
                    case 0x0001: // AV_MEDIA_INDICATION (no timestamp prefix)
                        listener.onVideoData(body);
                        sendMediaAck(out, tls, ackCounter);
                        break;
                    default:
                        listener.onStatus("Session: unhandled VIDEO message id=0x"
                                + Integer.toHexString(msg.messageId) + " (" + body.length + " bytes, "
                                + "videoChannelOpen=" + videoChannelOpen + " videoSetupDone=" + videoSetupDone + ")");
                }
                continue;
            }

            listener.onStatus("Session: unhandled message on channel " + msg.channelId + " id=0x"
                    + Integer.toHexString(msg.messageId) + " (" + body.length + " bytes)");
        }
        listener.onSessionEnded("stopped");
    }

    private AaProtocol.SocketMessage readControlMessage(InputStream in, AaTlsSession tls) throws Exception {
        return AaProtocol.readFrame(in);
    }

    /**
     * Sends AVMediaAckIndication on the video channel to replenish the phone's "unacked" media
     * credit (granted as max_unacked=1 in AVChannelSetupResponse). This is NOT optional: a real,
     * working VW MIB2 cluster-mirroring project's own documented failure mode confirms that with
     * no acks sent back, the phone sends exactly one frame and then stalls forever waiting for a
     * credit that never arrives - so this call happens right after every consumed AV_MEDIA(_WITH_
     * TIMESTAMP)_INDICATION, before the next frame can be sent. See AaControlMessages.
     * buildAvMediaAckIndication's doc comment for the source of this finding.
     */
    private void sendMediaAck(OutputStream out, AaTlsSession tls, long[] ackCounter) throws Exception {
        ackCounter[0]++;
        AaProtocol.writeFrame(out, VIDEO_CHANNEL_ID, 0x8004 /* AV_MEDIA_ACK_INDICATION */,
                tls.encrypt(AaControlMessages.buildAvMediaAckIndication(0, ackCounter[0])), true);
    }

    private byte[] decryptIfNeeded(AaProtocol.SocketMessage msg, AaTlsSession tls) throws Exception {
        return msg.encrypted ? tls.decrypt(msg.body) : msg.body;
    }
}
