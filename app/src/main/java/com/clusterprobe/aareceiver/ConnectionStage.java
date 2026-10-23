package com.clusterprobe.aareceiver;

/**
 * Every real step between "no phone attached" and "a decoded Android Auto video frame is drawn
 * on Display 2". Deliberately explicit and granular, rather than one big "connected/not
 * connected" flag, because the whole point of this diagnostic (per the project's standing rule:
 * "do not claim a working receiver until a real phone connection and video frame have been
 * demonstrated") is to show honestly which of these is real, which is wired up but unverified,
 * and which doesn't exist yet at all.
 *
 * Status progression per stage is tracked separately (see AndroidAutoReceiver.StageStatus) -
 * this enum is just the ordered list of what has to happen.
 */
public enum ConnectionStage {
    USB_DEVICE_ENUMERATION("Enumerate attached USB devices"),
    USB_PERMISSION("Request/obtain UsbManager permission for the candidate device"),
    AOAP_PROTOCOL_QUERY("AOAP GetProtocol control transfer (ACCESSORY_GET_PROTOCOL, 0x33)"),
    AOAP_SEND_IDENTIFYING_STRINGS("AOAP SendString x6 (ACCESSORY_SEND_STRING, 0x34)"),
    AOAP_START_ACCESSORY("AOAP StartAccessory control transfer (ACCESSORY_START, 0x35)"),
    ACCESSORY_REOPEN("Detect the phone's new vendor/product ID after re-enumerating in accessory "
            + "mode (or recognise it's already there from a prior run), then open it directly via "
            + "UsbDeviceConnection + claimInterface + bulk IN/OUT endpoints - NOT "
            + "UsbManager.openAccessory/UsbAccessory, which is the API for the opposite role (an "
            + "app that IS the accessory being opened by someone else); ClusterProbe is the host "
            + "here, so the re-enumerated phone is just a regular UsbDevice to us"),
    VERSION_EXCHANGE("Send VersionRequest, read VersionResponse - the first real plaintext "
            + "protocol message, over the bulk endpoints, before any TLS starts"),
    SSL_HANDSHAKE("TLS handshake over the raw accessory stream (Android Auto control channel)"),
    AUTH_COMPLETE("AuthCompleteIndication received - session authenticated"),
    SERVICE_DISCOVERY("ServiceDiscoveryRequest/Response - learn which channels the phone offers"),
    VIDEO_CHANNEL_OPEN("Open the AV (video) channel, negotiate resolution/FPS"),
    VIDEO_FRAME_DECODED("First H.264 frame actually decoded and drawable"),
    WIRELESS_BOOTSTRAP("Wireless session bootstrap - Bluetooth AA-Wireless service + Wi-Fi credential "
            + "exchange + local hotspot hosting (separate track from the USB path above - see "
            + "docs/H7_DISPLAY_ARCHITECTURE.md; aasdk itself does not implement this part at all)");

    public final String description;

    ConnectionStage(String description) {
        this.description = description;
    }
}
