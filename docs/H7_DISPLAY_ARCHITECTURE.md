# Haval H7 — Factory Display Layer Investigation

Status: investigation complete to the limit this sandbox's tooling allows. Read-only
analysis only — nothing here was produced by modifying vehicle coding, display config,
or any signature-protected interface.

## 0. Method and a disclosed limitation

Sources examined:

- `GWMMultiDisplay.apk` / `.odex` / `.vdex` (factory system app, `sharedUserId="android.uid.system"`)
- `cluster-logs.zip`: four real on-device captures — `android-auto-log.txt` (4555 lines),
  `display-log.txt` (453 lines, `dumpsys display`-style), `layers-log.txt` (150 lines,
  WindowManager container tree), `window-log.txt` (387 lines, `dumpsys window windows`-style)
- Prior work in this project: GwmAdapterServer.apk, Kafo themes APK (see project notes doc)

**Limitation, stated up front as instructed:** this sandbox has no working dex
disassembler. `pip install androguard`, and fetching jadx/apktool/baksmali via `gh`/`curl`,
all fail here (DNS/proxy restrictions — confirmed, not assumed). The `.vdex`'s actual
bytecode is Compact Dex (confirmed via its own header: magic `cdex001` nested inside
`vdex019`), and I could not convert or disassemble it. Everything below the method
and resource-name level — i.e., anything that would require reading actual bytecode
instructions — is **not available from this sandbox**, full stop. What follows instead
combines:

1. String/resource/manifest extraction from the APK and vdex (class names, method names,
   log tag strings, intent actions, permission names, layout/config resource names), and
2. **Live runtime evidence from your four logs** — actual `dumpsys` state and actual
   logcat lines captured on your car while it was running. This is the stronger evidence
   and is cited separately from the string-level findings below.

Where a finding comes only from strings, it's marked **(static)**. Where it comes from
your actual logs, it's marked **(live)** — this is confirmed runtime fact, not inferred
from naming.

## 1. Reconstructed classes and relevant methods

**(static, from `GWMMultiDisplay.vdex` string table)** — confirmed to exist in the
factory app, package `com.gwm.android.multidisplay`:

| Class | Role (from name/strings) |
|---|---|
| `GwmApplication` | app entry point |
| `InstrumentClusterController` | drives cluster rendering — constructor and methods `<init>`, `b`, `c` appear live in your logs (section 2) |
| `InstrumentClusterRenderingServiceImpl` | the declared `<service>` in the manifest (see §2) |
| `ClusterFunctionPresentation` | a `Presentation` subclass — standard Android secondary-display rendering, used for the cluster function UI |
| `IClusterListener` (+ `$Stub`, `$Stub$a`, `$Default`) | an AIDL callback interface, less obfuscated than GwmAdapterServer's equivalent |
| `IClusterViewManager` | a second AIDL interface — no exported/permission detail recoverable without disassembly |
| `InnerClusterListener` | internal listener implementation |
| `BaseView` (+`$a`) | widget base class |
| `CardLocation` (+`$a`/`$b`), `CacheView` (+`$a`/`$b`), `CardLiveData` | widget position/caching model — these are what the per-model `.config` files (§4) position |
| `RemoteViews` (GWM's own, +`$a`–`$h`, not `android.widget.RemoteViews`) | cross-process widget rendering helper |

Layout resources confirming `Presentation`-based rendering: `presentation_cluster_function_view.xml`
(+ `_d03`/`_ec24` model variants), `presentation_cluster_popup_view.xml`, `presentation_whud_view.xml`.

**(live)** Your logs independently confirm two of these classes are actually running, under
process PID 2609 (the `com.gwm.android.multidisplay` process):

```
window-log.txt / android-auto-log.txt (same PID 2609):
  W/ContextImpl( 2609): ... com.gwm.android.multidisplay.InstrumentClusterController.c:6
                            com.gwm.android.multidisplay.InstrumentClusterController.b:4
                            com.gwm.android.multidisplay.InstrumentClusterController.<init>:8
  I/ClusterManager( 2609): MultiDisplay gwm_sendHeartBeat success cnt = 6390
```

The second line is important: PID 2609's log tag `ClusterManager` is sending a repeating
**heartbeat** ("success cnt = 6390" — thousands of cycles by the time of capture) described
as "MultiDisplay". Combined with the `QnxReadyLiveData`/`isQnxReady` strings found earlier
in the vdex (static), this is consistent with `InstrumentClusterController` maintaining a
live handshake with a non-Android (likely QNX) controller on the other side of the
cluster's video output — not just drawing a local `Presentation` window. This is inference
from naming + a real heartbeat log line, not a confirmed protocol.

Also confirmed live: GWM's own Android Auto host app, `com.gwm.app.aap`, runs classes
`AAStatusManager`, `ProjectionManager`, `AndroidAutoPresenter`, `AndroidAutoModel`,
`ConnectionStateMachine`, and uses Google's own AA sink library
`com.google.android.projection.sink.navigation.NaviFocusManager` /
`com.google.android.projection.sink.gal.GalUtils` — specifically a method chain:

```
android-auto-log.txt:1439
  ...GalUtils.notifyNativeMap:828  com.gwm.app.aap.model.AndroidAutoModel.notifyNativeMap:512
```

`notifyNativeMap` is a real method name in Google's own AA sink SDK, not a GWM invention —
it signals to the phone's AA session whether the head unit wants AA's own rendered map
frame or wants to show its own ("native") map instead. This matters for Option C (§6).

No evidence of `ClusterManagerService` or a Beantechs `IMultidisplayService` was found in
any file available here (not in the manifest, not in the vdex string table, not in any log
line). I'm not asserting they don't exist — only that nothing in the material provided
confirms them.

## 2. How the factory gauges reach Display 2 — confirmed from your logs

`display-log.txt` gives the authoritative displayId → physical mapping (this is
`dumpsys display`, live from your car):

| displayId | Name | Resolution | layerStack |
|---|---|---|---|
| 0 | "Built-in Screen", `local:0` | 1920×1080 | 0 |
| 1 | "HDMI Screen", `local:1` | 1920×720 | 1 |
| 2 | "HDMI Screen", `local:3` | 1920×720 | 2 |
| 3 | "HDMI Screen", `local:4` | 480×240 | 3 |

This matches the brief's numbering exactly (0=main screen, 1=nav map, 2=gauges, 3=HUD).

`window-log.txt` then gives, for **displayId=2** specifically:

```
Display: mDisplayId=2
  mCurrentFocus=Window{99d29aa u0 com.gwm.android.multidisplay}
  mDisplayInfo=DisplayInfo{"HDMI Screen", uniqueId "local:3", ... layerStack 2, ...
                            FLAG_PRESENTATION, ...}
```

So: **Display 2's one and only focused top-level window belongs to `com.gwm.android.multidisplay`.**
`layers-log.txt`'s WindowManager container tree for this same display root shows only two
packages present at all: `com.gwm.android.multidisplay` and `com.smartlink.smartsplit` (the
latter twice, likely a split-screen helper, not gauge content). No other app, including
yours, is present on this display's window stack unless launched there.

Manifest extraction (static) confirms the mechanism: `GWMMultiDisplay.apk` declares

```
<service android:name="com.gwm.android.multidisplay.InstrumentClusterRenderingServiceImpl"
          android:permission="android.car.permission.BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE" />
```

`BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE` is a **real, standard AOSP/Android Automotive
permission**, not a GWM invention — it's the documented extension point `CarInstrumentClusterManager`
on the car-service side binds to. This, plus the `ClusterFunctionPresentation` /
`presentation_cluster_function_view.xml` resources, is consistent with: the gauges are
drawn by a standard Android `Presentation` targeting displayId 2, hosted inside a service
registered through AAOS's own instrument-cluster-renderer extension point — not some fully
proprietary mechanism outside Android's documented API surface.

## 3. How the Android Auto map reaches Display 1 — confirmed from your logs

Same two logs, for **displayId=1**:

```
display-log.txt: displayId=1, "HDMI Screen" local:1, 1920×720, layerStack 1
window-log.txt:
  Display: mDisplayId=1
  mCurrentFocus=Window{866de2f u0 com.gdc.carplay}
```

`layers-log.txt`'s container tree for this display root lists exactly two packages:
`com.gwm.app.aap` (GWM's own Android Auto *host*, with a `SurfaceView`) and `com.gdc.carplay`
(CarPlay, also with a `SurfaceView`) — whichever protocol's phone is currently connected
owns this display's focused window. Both are genuine `SurfaceView`-backed projection
surfaces, i.e. this is where AA/CarPlay's video frame is actually decoded and shown, not
a thumbnail or a cached image.

`android-auto-log.txt` separately confirms `com.gwm.app.aap`'s AA pipeline is the standard
Google Android Auto *host* stack running on top of GWM's own manager classes
(`AAStatusManager`, `ProjectionManager`, `AndroidAutoPresenter`), using Google's own
`com.google.android.projection.transport.*` and `com.google.android.projection.sink.*`
classes (confirmed class names in real stack traces, e.g. line 818-820, 1439-1440). This is
the ordinary AA-on-Android-Automotive integration pattern, not something unique to this car.

## 4. How the two displays are composited — the key finding

This is the most important result of this investigation, and it's drawn directly from
your live logs, not from strings.

**Display 1 and Display 2 are two completely separate, independent WindowManager display
roots (`layerStack` 1 and 2 respectively), each with exactly one focused top-level app
window, and neither app's window ever appears in the other display's container tree.**
`com.gwm.app.aap`/`com.gdc.carplay` own displayId 1 exclusively; `com.gwm.android.multidisplay`
owns displayId 2 exclusively. This is visible directly in `window-log.txt`'s per-display
`mCurrentFocus` lines and `layers-log.txt`'s container listing for each display root — it
isn't an inference, it's what `dumpsys window` and the WM container dump actually show.

The consequence: **there is no Z-order relationship between display 1 and display 2 inside
Android's own window stack to change.** "Display 2 renders above display 1 on the physical
cluster panel" is not a WindowManager/SurfaceFlinger fact about layer ordering within one
display — these are two separate Android displays that don't share a compositor stack at
all. Whatever merges their two independent video outputs into the one physical cluster
panel the driver sees happens **downstream of Android entirely** — in a hardware display
controller or a separate compositor (most likely QNX-based, given the `QnxReadyLiveData`/
`isQnxReady` strings in `InstrumentClusterController` and the `gwm_sendHeartBeat` log line
tying that same class to a live external handshake).

This directly answers the premise behind "swap display 1 and display 2 in the vehicle
coding": **there is no Android-side display-ID or layer-order setting that does this.**
Even setting aside the signature-protection boundary on vehicle coding, the brief's own
instruction not to assume changing display IDs changes layer order turns out to be exactly
right — changing a display ID assignment wouldn't touch whatever external
component is actually compositing the two video feeds, because that component isn't part
of the Android window stack this investigation can see into at all. I have not identified
what that external component is or how it's configured, and given the signature-protection
and vehicle-coding boundary that applies throughout this project, I wouldn't attempt to
find out by experimentation on your car.

## 5. Does the map fill the full 1920×720 area?

**Confirmed (live):** displayId 1 itself is exactly 1920×720 (`display-log.txt`), and its
only occupant is `com.gwm.app.aap`'s/`com.gdc.carplay`'s `SurfaceView` window (`layers-log.txt`,
`window-log.txt`) — there's no second app window on that display cropping or windowing the
map down. So **Android's own window for the AA/CarPlay projection surface is the full
1920×720 of displayId 1** — the visual cropping you see on the physical cluster (map
visible only in the middle, gauges around it) is not Android windowing the map smaller; it
happens at the same external-compositor stage described in §4, where display 2's
(opaque, by all available evidence) gauge graphics sit on top of display 1's full-size map
in the final composited image.

I found nothing in either the AA/CarPlay `SurfaceView` creation calls or the connection
logs (`android-auto-log.txt`) indicating the phone is asked to render anything other than
a standard full-frame AA map — no visible resize/crop request, no alternate "cluster"
layout negotiated over the AA protocol in these logs.

## 6. Evidence for/against the three options in the brief

**Option A — present the existing AA map surface above the gauges.**
Partially supported, partially ruled out. The map surface is real, full-size, and
confirmed to already exist independently on displayId 1 (§3, §5) — so there's no need to
reconstruct or re-decode AA's video. But "above the gauges" requires controlling the
external compositor described in §4, which is outside Android's documented window stack
and outside anything this investigation (or this project's standing boundary) can safely
touch. **Not achievable through a supported Android-level interface** based on everything
found here; it would require changing whatever composites the two display outputs, which
is firmware/vehicle-coding territory, not an app-level change.

**Option B — factory display composition / `CLUSTER_TYPE` values.**
I searched every string table available (`GWMMultiDisplay.apk`/`.odex`/`.vdex`) and all
four logs for `CLUSTER_TYPE`, `tbt_only`, `map_tbt`, and `map_only` verbatim. **None of
these strings appear anywhere in the material available to this investigation.** This
doesn't rule out the values existing in `com.gwm.app.aap`'s own APK (which wasn't provided
here) or in a config file not captured by these logs — but I can't confirm them from what
I have, and I'm not going to guess a property name and suggest testing it against a live
instrument cluster. Confirmed instead (§4): layer order between display 1 and display 2
is not a SurfaceFlinger/WindowManager-level setting at all, so even if a `CLUSTER_TYPE`
value exists, it most likely selects *content* for `com.gwm.android.multidisplay` to draw
(e.g. a turn-by-turn-only widget vs. full dial set — consistent with the per-model
`.config` files in §7 positioning small media/popup widgets) rather than controlling
inter-display Z-order.

**Option C — alternative presentation (consume AA's guidance data, not its video).**
This remains the most promising supported direction, and your own logs add one new, real
data point for it: `com.gwm.app.aap.model.AndroidAutoModel.notifyNativeMap` (§1, live,
line 1439) calls into Google's own `GalUtils.notifyNativeMap`/`NaviFocusManager.notifyNativeMap`
— real methods in Google's AA sink SDK that exist specifically to tell the connected phone
whether the head unit wants AA's rendered video frame or wants to supply its own native map
instead. That's a genuine, supported hook in the standard AA protocol GWM is already using
— not something hypothetical. It doesn't, by itself, give turn-by-turn guidance data to a
third app (your own cluster module would still need to register through AAOS's standard
`android.car.cluster.renderer.NavigationRenderer`/`CarInstrumentClusterManager` extension
point — confirmed present in `GWMMultiDisplay.vdex`'s string table, §1 — which is exactly
the mechanism the car-service side already uses to let a cluster-rendering service receive
guidance data from whatever navigation app is active, AA included). I have not found, in
the material provided, how GWM wires `notifyNativeMap` through to that extension point, or
whether it's wired at all on this build — that would need either the `com.gwm.app.aap` APK
itself or working bytecode disassembly, neither of which is available here.

## 7. Supporting detail: per-model `.config` files

`GWMMultiDisplay.apk`'s `assets/` directory contains 17 per-vehicle-model JSON config files
(e.g. `sa8155_v35.config`, `gwmb07.config`), each a plain-text JSON map of small widget
positions, keyed by numeric theme ID, for two widget types only — `media` and `popup`:

```
sa8155_v35.config: {"media":{"3":{"x":136,"y":224},"4":{"x":136,"y":224},"5":{"x":136,"y":224}},
                     "popup":{"3":{"x":691,"y":438},"4":{"x":691,"y":438},"5":{"x":71,"y":190}}}
```

These position small overlay widgets (media/notification popups), consistent with the
"Guide Mode = theme ID 4" fact already established earlier in this project — they are not
a map-layout or display-composition control, and don't bear on the display-1/display-2
question.

## 8. Direct answers to the brief's questions

- **Can the existing AA map be displayed above the decorative factory gauges through a
  supported interface?** No — not through anything confirmed here. The map's full-size
  surface exists on displayId 1 already, but the gauges-on-top compositing happens outside
  Android's window stack (§4), and nothing in the available files exposes a supported
  Android-level control for that external stage.
- **Can the factory system expose a map-focused layout without interfering with required
  instruments?** Unconfirmed either way. No `CLUSTER_TYPE`/`tbt_only`/`map_tbt`/`map_only`
  evidence was found anywhere in the files provided (§6, Option B).
- **Can ClusterProbe reuse the existing map surface?** Not directly — it's owned
  exclusively by `com.gwm.app.aap`/`com.gdc.carplay` on displayId 1 and never shares its
  window stack with anything else (§4). ClusterProbe can, however, pursue the same
  turn-by-turn-*data* route already partly used by GWM's own stack (§6, Option C).
- **Best technically supported approach:** Option C — register ClusterProbe through
  AAOS's standard `NavigationRenderer`/`CarInstrumentClusterManager` extension point (the
  same one `GWMMultiDisplay` already implements for the factory cluster) to receive AA's
  turn-by-turn guidance data, and draw your own full-width map/dash around it on display 2
  — this needs no change to vehicle coding, no signature-protected access, and doesn't
  touch the external compositor described in §4 at all.

## 9. Most promising safe next step

Determine, while parked, whether `android.car.cluster.renderer.NavigationRenderer` is
reachable as a normal app from inside ClusterProbe at all on this build — i.e. whether
registering a cluster-renderer-receiver through the standard AAOS APIs actually gets GWM's
car service to route AA guidance callbacks to it, or whether (as with `IGwmAdapterService`
earlier in this project) GWM's implementation only talks to its own `InstrumentClusterRenderingServiceImpl`
and ignores a third-party registrant. This is testable with a small, fully reversible
change: add a diagnostic-only class to ClusterProbe that calls the standard
`CarInstrumentClusterManager` registration API and logs whatever callbacks (or silence) it
gets back while Android Auto is actively navigating — no display, coding, or configuration
change, nothing written anywhere, parked testing only, and trivially removable if it
doesn't yield anything. I have not implemented this yet and am not proposing to touch
Newcastle Dash, the Shizuku integration, or the live GWM vehicle-data reader to do it.

**Update (v0.18):** implemented as `NavigationRendererProbe.java`, wired into MainActivity as
"Probe NavigationRenderer / cluster-renderer service". Runs two read-only checks: (1)
`CarNavigationStatusManager.getInstrumentClusterInfo()` — public, non-privileged, reports
whatever cluster capabilities the registered renderer has advertised; (2) a plain
`bindService()` against GWM's own `InstrumentClusterRenderingServiceImpl`, to directly confirm
whether its `BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE` requirement actually rejects a normal
app. Not yet run on-car — next on-car test should use this button and report what each check
logs.

## 10. Addendum: how display 1 is actually shared between Android Auto and CarPlay

Not covered above, but visible in the same `window-log.txt`/`layers-log.txt`/`android-auto-log.txt`
captures and worth recording since it bears on when/whether the probe in §9 will see anything:

- **Both apps keep a persistent window on display 1 at once, not just the active one.**
  `layers-log.txt`'s container tree for display 1 lists two `SurfaceView`-backed windows in its
  "above app windows" layer simultaneously — one for `com.gwm.app.aap` (token `3afd66c`), one for
  `com.gdc.carplay` (token `866de2f`) — both present in the same capture. `window-log.txt` shows
  only one as `mCurrentFocus` (CarPlay, in this capture) — so GWM doesn't create/destroy the
  window per session, it parks both and switches focus between them.
- **There's a named arbitration mechanism for it.** `GDC_CARPLAY`'s own log tag (PID 2920) emits
  lines like `ModeStatusBase onStatusChange borrowId:2, isActive:false` /
  `borrowId:4, isActive:true` (`android-auto-log.txt` ~line 1181, 1225) — CarPlay's state machine
  models taking over the shared projection slot (and likely the audio/mic route under a different
  `borrowId`) explicitly as *borrowing* it from whichever protocol would otherwise hold it.
- **CarPlay also keeps a second, separate window on display 0** (the main infotainment screen) —
  a background+`SurfaceView` pair in display 0's own "above app windows" layer (`layers-log.txt`,
  near the `com.shere.easytouch.holo`/HVAC Container entries), independent of its display-1
  window — presumably its own connection/settings UI.
- **CarPlay receives a live occlusion feed.** `SysDialogStatus onGwmSysDialogChanged` log lines
  (`android-auto-log.txt` ~1217-1250) report every system overlay's window type, bounds and
  visibility to CarPlay in real time (voice-assistant HMI bubble, HVAC panel, the EasyTouch
  floating button, CarPlay's own dialog) - a mechanism that doesn't appear anywhere in
  `com.gwm.android.multidisplay`'s (the gauge side's) logging in these captures.

Practical implication for §9's probe: whichever of `com.gwm.app.aap`/`com.gdc.carplay` is the
current "borrower" of display 1 is the one actually feeding guidance data, so note which
protocol/phone is connected when running the probe - a null/silent result with CarPlay active
doesn't rule out Android Auto's path working, and vice versa.

## 11. Closed line of inquiry: display-capture fallback (confirmed dead, on-car)

Tested live on-car via Shizuku's shell-UID access (read-only, parked): whether
`screencap`/`screenrecord` could grab display 1's live AA/CarPlay map directly, as a fallback
to feed into a ClusterProbe-drawn overlay if the guidance-data route (§6 Option C) doesn't pan
out. Confirmed dead, with evidence, not just untried:

- `adb shell find /system /vendor -iname "screencap*"` and `-iname "screenrecord*"` both
  returned **nothing** - neither binary exists anywhere on this device's `/system` or `/vendor`
  partitions.
- `adb shell /system/bin/screencap ...` (full path, bypassing `PATH` entirely) failed with
  `not found`, exit code 127 - genuinely absent, not merely off the shell's `PATH`.
- `/system/bin` does contain `tinycap` - this is unrelated (ALSA/tinyalsa **audio** PCM
  capture, not display/video capture), a name collision only.
- Every display already reports `FLAG_SECURE` in the `dumpsys display` capture used in §2.

Both standard AOSP display-capture routes are now ruled out independently: MediaProjection
(no permission dialog shipped on this head unit, per earlier on-car testing) and
`screencap`/`screenrecord` (binaries stripped entirely). `screencap`/`screenrecord` are present
on essentially every stock Android build, so their absence here, combined with `FLAG_SECURE` on
every display, reads as a deliberate lockdown rather than an oversight. This closes off
"copy the AA video frame directly" as a viable fallback - the two remaining live candidates are
§9's guidance-data probe (v0.18, not yet run on-car) and an independently-drawn map from GPS +
map tiles, with no dependency on AA/CarPlay's own video at all.
