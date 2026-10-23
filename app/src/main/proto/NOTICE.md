# Vendored from aasdk

The `.proto` files in this directory are vendored **verbatim and unmodified** from:

- Project: aasdk (https://github.com/f1xpl/aasdk)
- Commit: `046b3b381595509d0939fa84b14a90978f46ff63`
- License: GNU GPLv3
- Copyright (c) 2018 f1x.studio (Michal Szwaj)

They are the real, public Android Auto protocol message definitions, used here only as a protocol
specification (compiled to Java message classes by the Gradle protobuf plugin). No other part of
aasdk - none of its C++ implementation - is used, linked, or reimplemented from its source anywhere
in this project.

If ClusterProbe is ever published or distributed beyond personal/private use, GPLv3's terms would
apply to anything incorporating these files - worth a deliberate look before that happens. See
`docs/H7_DISPLAY_ARCHITECTURE.md`'s "Android Auto receiver" section for the full rationale.

**Do not move these files into a subfolder.** They must stay flat, directly under
`app/src/main/proto/`. Their own `import "Foo.proto";` statements are same-directory relative
(that's how they shipped in aasdk's own `aasdk_proto/` directory), so protoc cannot resolve them if
they're nested - this is exactly what broke the first CI build that added them (every cross-file
import failed with "File not found").

## Second source: aa-proxy-rs (v0.32, wireless bootstrap)

`AaWirelessStartRequest.proto` and `AaWirelessInfoResponse.proto` are also vendored field-layout
copies (renamed message/enum/file names only - wire format and field numbers unchanged), from:

- Project: aa-proxy-rs (https://github.com/aa-proxy/aa-proxy-rs)
- Commit: `5a6887d427f4b6f6395bf1d3d7d4d31f805e4f98`
- License: GNU GPLv2
- Original files: `src/protos/WifiStartRequest.proto`, `src/protos/WifiInfoResponse.proto`

These are the real messages sent over the Android-Auto-Wireless Bluetooth RFCOMM bootstrap channel
(service UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`), confirmed against aa-proxy-rs's own working
`send_params()` (head-unit role) in `src/bluetooth.rs`. They use `java_package`/`java_outer_classname`
options (unlike the aasdk files above) specifically so they land in their own Java package and never
collide with the aasdk-derived classes, regardless of either set's proto `package` declarations. See
`docs/H7_DISPLAY_ARCHITECTURE.md`'s "v0.32: wireless bootstrap" section for the full sequence this
pairs with (`WifiInfoRequest`/`WifiStartResponse`/`WifiConnectStatus` are deliberately NOT modeled as
.proto messages here - in the real protocol their bodies are either empty or discarded/only
checked byte-wise, exactly as aa-proxy-rs's own `read_message()` treats them, so ClusterProbe reads
them the same way: by message-ID header only, via `AaWirelessBootstrap`'s raw framing helper, not a
generated class).
