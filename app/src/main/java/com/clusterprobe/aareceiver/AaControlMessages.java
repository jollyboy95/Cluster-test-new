package com.clusterprobe.aareceiver;

import java.util.List;

/**
 * Builds/parses the real protobuf messages used right after the SSL handshake, per the vendored
 * aasdk .proto field layouts (app/src/main/proto/AuthCompleteIndicationMessage.proto,
 * ServiceDiscoveryRequestMessage.proto, ServiceDiscoveryResponseMessage.proto,
 * ChannelDescriptorData.proto, AVChannelData.proto, VideoConfigData.proto,
 * ChannelOpenRequestMessage.proto, ChannelOpenResponseMessage.proto,
 * AVChannelSetupRequestMessage.proto, AVChannelSetupResponseMessage.proto) and the real message-
 * role/direction confirmed by reading aasdk's actual ControlServiceChannel.cpp on 2026-10-23: as
 * the head unit, THIS side sends AuthCompleteIndication and ServiceDiscoveryResponse, and
 * receives ServiceDiscoveryRequest and (per-channel) ChannelOpenRequest/AVChannelSetupRequest -
 * the reverse of what an earlier pass through this project assumed before that source was read.
 *
 * Encoded/decoded with MiniProto rather than generated protobuf-java classes - see MiniProto's
 * own doc comment for why.
 */
public final class AaControlMessages {

    public static final int STATUS_OK = 0;
    public static final int STATUS_FAIL = 1;

    public static final int AV_STREAM_TYPE_VIDEO = 3;
    public static final int VIDEO_RESOLUTION_480P = 1;
    public static final int VIDEO_RESOLUTION_720P = 2;
    public static final int VIDEO_FPS_30 = 1;

    public static final int AV_SETUP_STATUS_OK = 2;
    public static final int AV_SETUP_STATUS_FAIL = 1;

    /** AuthCompleteIndication { required Status.Enum status = 1; } */
    public static byte[] buildAuthCompleteIndication(int status) {
        return new MiniProto.Writer().varint(1, status).toByteArray();
    }

    public static final class ServiceDiscoveryRequest {
        public final String deviceName;
        public final String deviceBrand;
        ServiceDiscoveryRequest(String deviceName, String deviceBrand) {
            this.deviceName = deviceName; this.deviceBrand = deviceBrand;
        }
    }

    /** ServiceDiscoveryRequest { string device_name = 4; string device_brand = 5; } */
    public static ServiceDiscoveryRequest parseServiceDiscoveryRequest(byte[] body) {
        List<MiniProto.Field> fields = MiniProto.parseFields(body);
        return new ServiceDiscoveryRequest(
                MiniProto.findString(fields, 4, "(unknown)"),
                MiniProto.findString(fields, 5, "(unknown)"));
    }

    /**
     * Builds a ServiceDiscoveryResponse advertising exactly one AV (video) channel - the minimum
     * needed to get the phone to attempt opening a video session with us. channelId must match
     * what's used later when reading the phone's ChannelOpenRequest/AVChannelSetupRequest on that
     * channel. videoResolution/videoFps are the VIDEO_RESOLUTION_ and VIDEO_FPS_ constants above.
     *
     * ServiceDiscoveryResponse { repeated ChannelDescriptor channels=1; string head_unit_name=2;
     *   string car_model=3; string car_year=4; string car_serial=5; bool left_hand_drive=6;
     *   string headunit_manufacturer=7; string headunit_model=8; string sw_build=9;
     *   string sw_version=10; bool can_play_native_media_during_vr=11; bool hide_clock=12; }
     * ChannelDescriptor { uint32 channel_id=1; AVChannel av_channel=3; }
     * AVChannel { AVStreamType.Enum stream_type=1; repeated VideoConfig video_configs=4; }
     * VideoConfig { VideoResolution.Enum video_resolution=1; VideoFPS.Enum video_fps=2;
     *   uint32 margin_width=3; uint32 margin_height=4; uint32 dpi=5; }
     */
    public static byte[] buildServiceDiscoveryResponse(int channelId, int videoResolution, int videoFps,
                                                         int dpi) {
        MiniProto.Writer videoConfig = new MiniProto.Writer()
                .varint(1, videoResolution)
                .varint(2, videoFps)
                .varint(3, 0) // margin_width
                .varint(4, 0) // margin_height
                .varint(5, dpi);

        MiniProto.Writer avChannel = new MiniProto.Writer()
                .varint(1, AV_STREAM_TYPE_VIDEO)
                .message(4, videoConfig);

        MiniProto.Writer channelDescriptor = new MiniProto.Writer()
                .varint(1, channelId)
                .message(3, avChannel);

        return new MiniProto.Writer()
                .message(1, channelDescriptor)
                .stringField(2, "ClusterProbe")
                .stringField(3, "Haval H7 Vanta Hybrid")
                .stringField(4, "2026")
                .stringField(5, "clusterprobe-001")
                .bool(6, false)
                .stringField(7, "ClusterProbe")
                .stringField(8, "H7-Display2")
                .stringField(9, "dev")
                .stringField(10, "0.36")
                .bool(11, false)
                .bool(12, false)
                .toByteArray();
    }

    public static final class ChannelOpenRequest {
        public final int priority;
        public final int channelId;
        ChannelOpenRequest(int priority, int channelId) { this.priority = priority; this.channelId = channelId; }
    }

    /** ChannelOpenRequest { int32 priority=1; int32 channel_id=2; } */
    public static ChannelOpenRequest parseChannelOpenRequest(byte[] body) {
        List<MiniProto.Field> fields = MiniProto.parseFields(body);
        return new ChannelOpenRequest(
                (int) MiniProto.findVarint(fields, 1, 0),
                (int) MiniProto.findVarint(fields, 2, -1));
    }

    /** ChannelOpenResponse { required Status.Enum status=1; } */
    public static byte[] buildChannelOpenResponse(int status) {
        return new MiniProto.Writer().varint(1, status).toByteArray();
    }

    /** AVChannelSetupRequest { uint32 config_index=1; } */
    public static int parseAvChannelSetupRequestConfigIndex(byte[] body) {
        List<MiniProto.Field> fields = MiniProto.parseFields(body);
        return (int) MiniProto.findVarint(fields, 1, 0);
    }

    /** AVChannelSetupResponse { required AVChannelSetupStatus.Enum media_status=1;
     *   required uint32 max_unacked=2; repeated uint32 configs=3; } */
    public static byte[] buildAvChannelSetupResponse(int mediaStatus, int maxUnacked, int configIndex) {
        return new MiniProto.Writer()
                .varint(1, mediaStatus)
                .varint(2, maxUnacked)
                .varint(3, configIndex)
                .toByteArray();
    }

    /**
     * AVMediaAckIndication { required int32 session=1; required uint32 value=2; } - per the
     * vendored app/src/main/proto/AVMediaAckIndicationMessage.proto. We (the head unit) send this
     * on the video channel after consuming media frames, to replenish the phone's "unacked" credit
     * (granted as max_unacked in buildAvChannelSetupResponse above). Found to be necessary, not
     * optional, by reading a real working VW MIB2 cluster-mirroring project's own documented
     * failure mode: with max_unacked set low (we use 1) and no acks ever sent back, the phone
     * sends exactly one frame and then stalls waiting for a credit that never arrives - it is not
     * just a bitrate/corruption risk at higher max_unacked, it is a hard requirement for any video
     * to flow past the first frame at all with the conservative value this project uses.
     */
    public static byte[] buildAvMediaAckIndication(int session, long value) {
        return new MiniProto.Writer()
                .varint(1, session)
                .varint(2, value)
                .toByteArray();
    }
}
