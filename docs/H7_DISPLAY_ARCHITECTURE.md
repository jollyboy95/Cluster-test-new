We need to keep the project grounded in reality: the actual factory APK/VDex/configuration exports are not in this repo, so the app-level work must stay diagnostic and non-invasive while the external binaries are treated as out-of-scope artifacts. This update adds a clearer boundary around what is confirmed versus what remains missing.

## v0.20 expanded interface scan

The `NavigationRendererProbe` diagnostic has been updated to do a broader reflection scan of the Android Automotive classes and service constants that may exist on this H7 build.

It now logs:

- whether the relevant Android Automotive classes are actually present (`android.car.Car`, `CarNavigationStatusManager`, `CarInstrumentClusterManager`, `NavigationRenderer`, etc.)
- which CREATE and service-constant strings the build exposes
- the actual `createCar(...)` overload signatures on this head unit
- the manager method names that the returned instance exposes
- the direct factory renderer bind result, which remains denied for a normal app

This keeps the app read-only and preserves the existing ClusterProbe functionality while collecting the precise evidence needed to determine what the real runtime API surface looks like on this Harman OS build.

## v0.21 additions to the same probe button

To cut down on reinstall cycles, the following were folded into the existing probe rather than
added as separate buttons - one press now also checks:

- The real `protectionLevel` of `BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE` and
  `CAR_NAVIGATION_MANAGER` directly from `PackageManager` (same technique as the GET_DATA probe),
  instead of relying on an assumption from AOSP source.
- Every `*_SERVICE` string constant `android.car.Car` declares on this build, with an actual
  `getCarManager(...)` attempt for each - not just which constants exist, but which managers a
  normal app can actually obtain here.
- `CarAppFocusManager.getAppTypeOwner(APP_FOCUS_TYPE_NAVIGATION)`, the standard AAOS way to
  identify which app currently holds navigation focus - ties to the AA/CarPlay "borrow"
  arbitration noted earlier in this project, so a result can be read against whichever protocol
  was actually active at test time.

All read-only, all via the same reflection approach, nothing added that writes or binds beyond
the existing renderer-bind check.

## v0.22: fixed a real bug in the probe itself, plus one new permission

The v0.21 on-car run gave two genuine new results and one that turned out to be a bug in this
probe, not a finding about the car:

- **Real result**: `CAR_NAVIGATION_MANAGER` is actually `signature + extra flags` protected on
  this build (`protectionLevel=0x12`, declared by `com.android.car`) - not "normal" as this
  file's earlier assumption (and the original code comment) had it. `BIND_INSTRUMENT_CLUSTER_RENDERER_SERVICE`
  stayed confirmed `signature` (`0x2`).
- **Real result**: the renderer-bind check reconfirmed GWM's `InstrumentClusterRenderingServiceImpl`
  rejects a normal app with `SecurityException`, as before.
- **Bug, not a result**: every one of the 44 `getCarManager()` calls in the all-managers sweep
  failed identically with `IllegalStateException: not connected`. This build's `android.car.Car`
  only has the `(Context, ServiceConnection)` / `(Context, ServiceConnection, Handler)` overloads
  (confirmed - no synchronous single-arg `createCar(Context)`), which connect to car service
  *asynchronously*. The probe was passing a `null` ServiceConnection and using the `Car` object
  immediately, before any real connection could exist - so the uniform failure measured a timing
  bug in this app, not a permission wall.

Fixed by connecting once, for real: a genuine `ServiceConnection` is passed to `createCar(...)`,
`car.connect()` is called if present on this build, and the probe waits up to 4 seconds for
`onServiceConnected()` before handing that one connected `Car` instance to every
manager-dependent check (all-managers sweep, cluster-info, nav-focus-owner). Only now are
`SecurityException`/denied results from those checks trustworthy.

Also added: a check for a permission that turned up in the service-constant scan but hadn't been
looked at yet - `android.car.permission.CAR_INSTRUMENT_CLUSTER_CONTROL`. It looks like it may
gate `android.car.cluster.CarInstrumentClusterManager` specifically, which is a different class
from `CarNavigationStatusManager` and - confirmed present on this build - has its own
`registerCallback`/`unregisterCallback`/`startActivity` methods. `startActivity` in particular is
the standard AAOS way to launch an Activity onto the instrument cluster display through car
service, a distinct and less-privileged path from binding GWM's rendering service directly. A new
check (`probeClusterManagerDetail`) now obtains that manager (while genuinely connected) and logs
the full signatures of those three methods, so if it's reachable here, the exact calling
convention is already in hand for a next step.

## v0.22 on-car result: Car-connect itself times out, plus a bug the result exposed

Running v0.22 on-car (three times) gave a consistent, real result: both `createCar(...)`
overloads timed out after 4s waiting for `onServiceConnected()` - `car.connect()` returned
without throwing, but no callback ever arrived and `isConnected()` read false at the end. Unlike
the GWM renderer bind (a normal `bindService()` call, which throws `SecurityException`
immediately when denied), `android.car.Car`'s connection to `car_service` doesn't appear to fail
the same way on this build - a permission/SELinux-level rejection here may simply never reach the
app as an exception at all. On its own this is already a meaningful negative result: a normal app
cannot get a working `android.car.Car` connection on this build, via any available overload -
which closes off the *entire* standard-AAOS-manager avenue, not just the specific permissions
already checked.

That result also exposed a real bug in the probe itself: the 4-8 second blocking wait was running
on whatever thread called `NavigationRendererProbe.run(...)`, which in `MainActivity` is the
probe button's `onClick` - the **main thread**. That's within Android's ANR window (5s for input
dispatch). Fixed in v0.23 by running the whole probe on its own background thread.

## v0.23: background thread + a logcat snapshot to explain the Car-connect timeout

Two changes:

- The probe now runs entirely on a background thread (`NavigationRendererProbe.run(...)` spawns
  it), not the button's `onClick` thread - the UI log panel updates the same way as before
  (`StatusListener` still posts to the main thread), this just stops the up-to-~14s worst case
  wait (8s Car-connect + 6s logcat dump) from blocking input.
- A new one-shot, read-only logcat snapshot (`CarServiceLogSnapshot`, reusing the existing
  Shizuku shell-UID `GwmLogcatUserService` mechanism via a new `dumpOnce(...)` AIDL method,
  completely separate from the live-vehicle-data stream) runs after the Car-connect step either
  way. It dumps the recent system log once (`logcat -d -b all -t 3000`) and reports only lines
  containing car-service/permission-denial keywords (`car_service`, `ICar`, `denied`,
  `SecurityException`, `avc:`, `Permission Denial`, etc.) - the only way left to find out *why*
  the connection times out with no exception of its own. Requires Shizuku to already be
  authorized; skips cleanly with a one-line note if it isn't.

## v0.24: reverse-read the real factory AndroidAuto.apk/.odex/.vdex (read-only, static analysis)

The user pulled the actual `AndroidAuto.apk`, `.odex` and `.vdex` off the car's system partition -
this is `com.gwm.app.aap`, GWM's own Android Auto host app, not a third-party or placeholder. All
analysis below is static and read-only: the APK was unzipped, the manifest's binary XML and the
vdex's embedded Compact Dex (`cdex001`) string tables were parsed directly (no decompiler was
available in the sandbox this was done in - no network access to install one), nothing was
patched, resigned, or written back anywhere.

**A claim correction, for the project record.** A separate AI session (referred to as "Copilot" in
the conversation) had produced a report naming specific classes/methods -
`Lcom/gwm/app/aap/presenter/AndroidAutoPresenter;` and `notifyNativeMap` - as evidence inside this
vdex. A complete, exhaustive scan of both dex blobs' string tables (227,257 entries, counts
cross-checked against the file's own declared `string_ids_size` for each blob) found **no trace of
either string anywhere in the file**. They do not exist in this binary. Two *other* claims from
the same report - `NavigationStatusService` and `INSTRUMENT_CLUSTER_START` - checked out exactly.
Treat anything else from that source with the same scrutiny; it is not reliably grounded in the
actual files.

**Confirmed, verified findings:**

- `com.gwm.app.aap` declares `sharedUserId` paired with the value `android.uid.system` in its
  manifest. This app runs *as* the system UID, platform-signed - not just "an app with more
  permissions declared." This is the structural reason it can reach the privileged
  cluster-renderer/`InstrumentCluster` path that `NavigationRendererProbe` has already confirmed
  denied (`SecurityException`) to a normal app: a different trust tier, reachable only by
  re-signing with GWM's platform key. That is squarely the kind of signature-protected boundary
  this project has stayed clear of throughout, and nothing here changes that.
- 67 `.properties` vehicle-profile assets ship inside the APK. `CLUSTER_TYPE` splits exactly
  34 `tbt_only` / 31 `map_tbt` / 2 `map_only`. **None of the 67 match this car** - no "H7", no
  2026 model year; the newest entries are 2024 generic GWM codes and 2022 Haval H6/Dargo. Which
  profile (if any of these) is actually active on this H7 Vanta is unknown - it may be a newer
  profile not present in this particular pulled copy, or it may fall back to `default.properties`.
- `CLUSTER_WIDTH`/`HEIGHT` (1920x720 in every profile sampled) is the full cluster canvas - it
  matches the physical cluster display exactly and confirms `CLUSTER_INDEX=2` is the same
  logical displayId 2 already mapped via `dumpsys display`. But `CLUSTER_DISPLAY_WIDTH`/`HEIGHT`
  (600x350 in every profile sampled, including both `map_only` ones) is the actual rendered
  inset size *within* that canvas. So `map_only` does not mean "a big map fills the cluster" -
  it means "show the map instead of turn-by-turn graphics," still inside that same modest inset.
- One independently-found, unresolved lead: `res/mipmap/mask_cluster_map.png` is **1920x620** -
  far larger than the 600x350 inset in every sampled profile. Something in this app can
  apparently render much larger than what's specified in the profiles checked so far. What
  triggers that was not found (would need real bytecode tracing - see limitation below).
- `res/layout/layout_cluster*.xml` confirm the cluster output is a `SurfaceView` (a video frame),
  consistent with everything already established about this architecture.
- Real (confirmed, not fabricated) protobuf navigation-protocol class/field names found in the
  string tables: `gal.NavigationStatusService`, `InstrumentClusterType`, `InstrumentClusterInput`,
  `InstrumentClusterAction`, `INSTRUMENT_CLUSTER_START`/`STOP`, plus maneuver/lane/distance/
  destination message names - a genuine structured navigation data channel from the phone, inside
  this privileged app's own process.
- `com.projection.action.CLUSTER_STATE` is declared as a `<protected-broadcast>` in this app's
  manifest. A `<protected-broadcast>` only restricts who may *send* that action system-wide -
  receiving it is unrestricted, same as any other broadcast. This is the one avenue found today
  that needs no special permission and no signature-level trust. **v0.24 adds
  `ClusterStateBroadcastListener`**, folded into the existing "Probe NavigationRenderer..."
  button/Stop pair: it registers for this action and logs anything received, including after the
  probe's other synchronous checks finish (the broadcast, if sent system-wide at all, likely only
  fires on a real mode change, not on a schedule). Whether `com.gwm.app.aap` actually sends it
  system-wide, and what it carries if so, is unknown until tested on-car.

**A tooling limitation worth recording plainly.** A hand-rolled parser was attempted to map each
class to its declared methods (`type_ids`/`method_ids`/`class_defs`/`class_data_item`), one level
past the plain string-table scan. It produced results that *looked* plausible but were
demonstrably wrong on inspection: it attributed `DISPLAY_TYPE_CLUSTER` to a class as if it were a
method, when it is actually a protobuf enum constant embedded in a descriptor string nearby in the
same table. That parser's output was discarded entirely rather than reported - every finding above
came only from the plain string-table scan (reliable: each entry is read independently via its own
`string_ids` array slot, no structural assumptions beyond that one lookup) or from direct manifest/
resource inspection. **Real method-body tracing - where AA video is decoded, how a `SurfaceView`
gets selected - was not achieved and is not achievable with what's available in this sandbox**: no
network access to install a real dex/smali toolchain (`androguard`/`jadx`/`baksmali` all failed on
DNS resolution). That would need to happen in an environment with that access, or be handed to a
real disassembler elsewhere.

**Bottom line.** This was a worthwhile correction and a real documentation gain, but it does not
open a new path for ClusterProbe to render the live AA video feed. The `android.uid.system`
finding, on top of the already-confirmed on-car `SecurityException`, closes that specific door
twice over - a full trace of `com.gwm.app.aap`'s video-decode/`SurfaceView` logic would explain
*how* the privileged app does it, not hand ClusterProbe a way to do it too, since the gate is
identity/signing, not a discoverable technique. The one live, testable, unprivileged thread left
is the `CLUSTER_STATE` broadcast listener added this version - not video, but possibly real
cluster/navigation state data, which would still be useful for ClusterProbe's own rendered
dash even if the literal video frame stays out of reach.

## v0.25: on-car confirmation round (Car-connect genuinely works now) + two closing diagnostics +
## phone-companion research (no standalone map built)

**On-car result, not yet in this doc:** a v0.24 run (or very close to it) showed `Car-connect`
actually succeeding for the first time - `createCar(Context, ServiceConnection) -> connected
(onServiceConnected fired)`. That makes the full 44-manager sweep's results trustworthy for the
first time: `cluster_service` and `car_navigation_service` were both cleanly denied with named
signature permissions (not a timing artifact), which is the final, confirmed word on the standard
AAOS cluster/navigation manager path - it closes, not "remains to be tried." A long list of
`GwmCar*Manager` classes (lighting, HVAC, seat control, driving control, DMS/DVR, bodywork,
parking, dock, fragrance, new-energy, tbox, fota, gnss, info, power, etc.) came back obtainable -
worth recording, but obtaining the manager handle only proves the `IBinder` is reachable, not that
any individual method on it is unprotected at call time. None of those methods have been called.
`CarAppFocusManager` (`app_focus`) was also obtainable - the one manager in that whole sweep that's
actually relevant to navigation-focus arbitration, but this build's manager has no
`getAppTypeOwner(int)` method, and the probe's old 12-name-capped method summary wasn't enough to
find what it's called instead.

**Two read-only diagnostics finished this version, both folded into existing buttons:**

- `NavigationRendererProbe`'s nav-focus-owner probe now logs `CarAppFocusManager`'s **complete**
  method list (full signatures, not capped at 12) plus a specific line for any method with "owner"
  in the name, so the real accessor on this platform can be read straight off the log instead of
  guessed at again.
- `CaptureHelper.checkCaptureIntentResolvable()` - a `PackageManager.queryIntentActivities()` check
  against `MediaProjectionManager.createScreenCaptureIntent()`, run from the "Test map capture"
  button BEFORE `startActivityForResult()` is ever called. An earlier on-car run already threw
  `ActivityNotFoundException` from that call; this confirms (rather than infers from one exception)
  whether the capture-consent screen is genuinely absent system-wide on this build. Never launches
  anything itself.
- `CarServiceLogSnapshot`'s keyword filter also now matches `com.gwm.app.aap`-specific tokens
  (`cluster_type`, `map_only`/`map_tbt`/`tbt_only`, `navigationstatusservice`, `cluster_display`,
  etc.) - worth re-running specifically **while Android Auto is actively projecting navigation**,
  since no prior on-car run was confirmed to be captured under that condition. This is the cheapest
  remaining way to find out which cluster profile/mode is actually active on this car, without
  touching anything protected.

**Direction change, explicitly recorded:** development briefly started toward an independently-
rendered live map (MapLibre + the phone's own GPS) as a fallback for Display 2, on the reasoning
that the AA-video-surface path was closed. That assumption was wrong in an important way: **the H7
head unit itself has no internet connection of its own** - Android Auto navigation on this car only
works because the Samsung phone supplies live Google Maps data over the wireless AA link. A
standalone map rendered directly on the head unit (open street-map tiles fetched by the head unit
itself, etc.) would need internet the head unit doesn't have, so that specific approach was
abandoned before any dependency was added to the build - **no MapLibre dependency, no new Activity,
no gradle change was committed for this.** Priority stays on reusing the live AA video/nav data
that's already working, with a phone-companion relay investigated as a fallback design only (not
built) in case the video-surface path stays closed.

**Phone-companion architecture - research only, nothing implemented:**

The real constraint is "head unit has no internet of its own," not "the phone has no internet while
Android Auto is active" - the phone obviously does, since Google Maps is already rendering live on
the car's screen through the existing wireless AA session. So the actual question is narrower: can
a *separate* local channel carry data from the phone (which has GPS, internet, and whatever Google
Maps is doing) to ClusterProbe on the head unit, without disrupting that AA session.

- **Wireless Android Auto's networking model**: a Bluetooth handshake establishes the session, then
  video/data flows over a dedicated Wi-Fi Direct link between phone and head unit (commonly 5GHz to
  avoid 2.4GHz contention). Bluetooth stays active throughout (call audio, media controls). Whether
  the phone's *regular* Wi-Fi (for its own internet, as opposed to mobile data) can stay connected
  at the same time as the Wi-Fi Direct link to the car is chipset/OEM-dependent and not something
  this project has verified on your specific Samsung phone - flagging as unconfirmed rather than
  assuming either way.
- That uncertainty mostly doesn't matter here, though: **Bluetooth and the cellular modem are
  separate radios from the Wi-Fi Direct link**, and BT+cellular concurrent with Wi-Fi is routine,
  non-experimental phone behaviour (this is exactly how a phone call, a smartwatch, and Android
  Auto itself already coexist). A companion app on the phone relaying data to the head unit over a
  **classic Bluetooth RFCOMM socket** (a different UUID/profile from whatever AA itself uses, over
  the same already-paired BT link) is architecturally the safer bet than trying to share the Wi-Fi
  radio, and needs on-car testing to confirm concurrency in practice, not just in theory.
- **Google Maps has no public API for a third-party app to read its live route/turn-by-turn state.**
  This was checked, not assumed. Google's actual Navigation SDK for Android is a separate, paid
  product for building *your own* turn-by-turn app with Google's routing data - it does not expose
  what the stock consumer Google Maps app is currently doing. There is no supported way to "tap
  into" the live Maps session itself.
- What **does** exist, confirmed by two independent small real-world open-source projects that do
  exactly this ([GMapsParser](https://github.com/3v1n0/GMapsParser),
  [gmaps_nav_hud_frame](https://pub-web-s2.flutter-io.cn/packages/gmaps_nav_hud_frame)): Google Maps
  posts a persistent **navigation notification** during an active route (next turn, distance,
  street name, a small maneuver arrow icon). A `NotificationListenerService` - a standard, public
  Android API, one-time user permission grant, no root - can read that notification's content as it
  updates. This is real precedent, not a guess. Known limits from those same projects: only what the
  notification actually shows is available (no raw polyline/route geometry, no literal map image);
  for an *existing* navigation session the notification can take up to ~a minute to first update
  (a freshly-started session updates immediately); and it depends on Google Maps' own notification
  layout, which can change between app versions and silently break the parser.
- The phone's own GPS (`FusedLocationProviderClient`, a standard API) gives live position/speed/
  bearing directly and is **completely independent of what navigation app is running or whether
  Maps cooperates at all** - a moving-position layer doesn't need any of the above.
- Net shape of a companion architecture, if this ever gets built: a small app on the phone reading
  (a) its own GPS directly and (b) the Maps navigation notification via `NotificationListenerService`,
  relaying both over a Bluetooth RFCOMM socket to a matching listener added to ClusterProbe on the
  head unit, which draws them into the existing Newcastle Dash map-window geometry. This gives live
  position + turn-by-turn *text/icon* state, not the literal rendered Google Maps image - a visually
  different, ClusterProbe-drawn result, not a mirror of AA's video.
- Not implemented. Nothing in this project currently requests Bluetooth, location, or notification-
  access permissions, and no companion-app code exists. This section exists so the design is on
  record before any code is written, per "don't build a standalone map until we establish how the
  navigation data will reach the head unit."

**What this version changes in the repo:** three read-only diagnostic additions
(`NavigationRendererProbe` full-method-list logging, `CaptureHelper`'s resolver check,
`CarServiceLogSnapshot`'s extra keywords) and this documentation. No new dependency, no new
Activity, no new permission declared, no existing feature touched or removed.

## v0.25 on-car result: two more avenues conclusively closed

Both new diagnostics returned results on-car, and both close out the thread they were built for -
neither is "worth re-checking," both are now settled:

- **`CarAppFocusManager`'s full method list (18 methods)**: `abandonAppFocus` (three overloads),
  `addFocusListener`, `removeFocusListener`, `isOwningFocus(OnAppFocusOwnershipCallback)`, plus
  `Object` methods. There is no method anywhere in that list that lets a normal app query "who
  currently owns navigation focus" after the fact - only ways to request/abandon focus for
  yourself, register for focus-*changed* callbacks going forward, and check whether your own
  callback owns it right now. `getAppTypeOwner(int)` isn't missing by name only - this build's
  manager has no equivalent accessor at all. Closed, not "guessed wrong, try again."
- **`CaptureHelper.checkCaptureIntentResolvable()`**: "NO activity on this build resolves the
  capture-consent intent... MediaProjection is not usable on this head unit via the normal
  app-facing API." The actual capture attempt immediately afterward failed the same way it always
  has (`ActivityNotFoundException`). Two independent checks now agree: MediaProjection is not a
  live possibility on this head unit, confirmed by direct `PackageManager` query, not inferred from
  one exception.

**Updated running tally of every normal-app-reachable avenue probed in this project, now all
either confirmed closed or confirmed-but-still-untested:**

| Avenue | Status |
|---|---|
| `CarInstrumentClusterManager` / `CarNavigationStatusManager` | Denied - signature permissions |
| Direct `bindService()` to GWM's renderer | Denied - `SecurityException` |
| `CarAppFocusManager` (query current nav-focus owner) | No such accessor exists on this build |
| `MediaProjection` (standard Android screen-capture API) | No consent activity exists on this build |
| `CLUSTER_STATE` broadcast | Registers cleanly; nothing received in any run so far - the one open, untested thread, and it needs a run while AA is actively navigating on-screen to mean anything either way |

Every standard, normal-app-reachable API surface this project has access to has now been tried and
closed with direct on-car evidence. The phone-companion architecture (researched, not built - see
above) is the only remaining forward path besides waiting on `CLUSTER_STATE`.

## v0.26: Android Auto receiver - ClusterProbe becomes its own AA receiver (new direction)

Given every normal-app path into GWM's existing Android Auto session is now confirmed closed (see
the table above), the project's direction changed: instead of trying to reach *into* GWM's
receiver, ClusterProbe starts building its **own**, independent Android Auto receiver - the
phone connects to ClusterProbe directly (over USB initially, wireless later), same as it connects
to GWM's factory receiver today, just a second option. This does not touch, bypass, or interfere
with GWM's receiver or car.ipk_setting.* in any way - it is a parallel connection to the phone,
the same category of thing hobbyist "DIY Android Auto head unit" projects
(OpenAuto/aasdk/Head Unit Revived) already do on other hardware. The user independently confirmed
**Head Unit Revived already works on this exact NOBO head unit** - both wired and wireless - which
is real, concrete validation this is achievable on this hardware before any of this project's own
code was written.

**The one unavoidable trade-off, also user-confirmed from that same test:** a phone holds one
active Android Auto session at a time. Using ClusterProbe's receiver means the phone connects to
*it* instead of GWM's factory receiver - getting GWM's own AA back required not opening the
third-party app and restarting the head unit. This is a swap, not a tap. Building our own receiver
doesn't change that trade-off; it only changes what the cluster gets to show while connected to it,
and lets us draw our own instruments around the video instead of being stuck with someone else's UI.

**Why not just reuse aasdk's C++ directly:** aasdk (https://github.com/f1xpl/aasdk, GPLv3, commit
`046b3b381595509d0939fa84b14a90978f46ff63`) is a real, comprehensive implementation of the AA
protocol, but it's written for desktop/embedded Linux - it depends on Boost, OpenSSL, and
**libusb-1.0 with direct kernel USB device access**, which a normal (non-rooted) Android app
cannot have; Android apps get USB access through `UsbManager`, which hands over an
already-permission-granted file descriptor instead. Making aasdk's C++ work inside a sandboxed
Android app would mean cross-compiling Boost/libusb/OpenSSL for Android via NDK *and* patching
aasdk's USB layer to accept an Android-supplied fd instead of opening the device itself - real,
precedented, but substantial toolchain work, and this project has no way to build or test any
native/NDK code at all in the sandbox it's developed in (no compiler, no device, nothing to verify
against). Writing unverified C++/JNI and presenting it as working would be exactly the kind of
"looks finished, isn't" result this project avoids.

**What's actually used from aasdk:** only `aasdk_proto/*.proto` - the real, complete Android Auto
protocol message definitions (auth, AV/video channel, bluetooth, sensor, control, input, etc.) -
vendored verbatim and unmodified into `app/src/main/proto/` (flat, not in a subfolder - see that
directory's NOTICE.md for why), compiled to Java message
classes by the Gradle protobuf plugin. None of aasdk's C++ implementation is used, linked, or
reimplemented from its source; the actual receiver logic here is original Java/Kotlin written
directly against Android's own public `UsbManager`/`UsbDeviceConnection` API and the public,
Google-documented AOAP handshake spec. **Licensing note:** the vendored `.proto` files are GPLv3 -
if this project is ever published/distributed rather than kept as a personal build, that license's
terms would apply to anything incorporating them; worth a deliberate look before any public release.

**A second real app was analyzed for grounding, read-only, the same way the factory AndroidAuto.apk
was analyzed in v0.24: DiPlay (the APK the user already has, for a BYD build).** Findings:
`liblocal_hotspot_radio.so` confirms the wireless bootstrap really does involve the receiver
hosting its own Wi-Fi hotspot (matches "my Samsung connects to the Haval's hotspot") - replicable
with Android's own standard `WifiManager` local-hotspot APIs, no special hardware implied. But
`libxcertplay_i2c.so` plus direct manifest references to `com.byd.amapservice`/`com.byd.service`/
`com.ts.car.someip.service` and an `AdbClusterActivity` component strongly suggest DiPlay's
specific *cluster-display* feature on BYD depends on BYD hardware (an I2C-addressable companion
chip) and privileged platform hooks - not a pure-software trick portable to this NOBO head unit.
This doesn't block anything here, though: ClusterProbe already has its own working path onto
Display 2 (`ClusterActivity`, proven since v0.20) - the receiver only needs to hand over a decoded
frame, not replicate BYD's specific injection mechanism.

**What this version actually implements (and what it explicitly does not):**

New, isolated package `com.clusterprobe.aareceiver`, wired into MainActivity as a clearly-labelled
"Android Auto receiver (experimental, OFF by default)" section with its own master toggle
(`AndroidAutoReceiver.enabled`, defaults to `false`) - the negotiation button does nothing at all
unless that toggle is explicitly turned on first, and even then only runs when pressed, with no
manifest-registered `USB_DEVICE_ATTACHED` receiver and no background listening, so nothing here
can interfere with the factory receiver or an existing phone pairing unless the user deliberately
triggers it with a phone physically plugged in for this specific test.

`ConnectionStage` enumerates every real step from "no phone attached" to "a decoded video frame is
drawable", each tracked with an honest status (`NOT_IMPLEMENTED` / `IMPLEMENTED_UNTESTED` /
`RUNNING` / `TESTED_WORKING` / `TESTED_FAILING`) - visible via the new "Show all connection-stage
statuses" button. As of this version:

- **Implemented, not yet tested on a real phone:** USB device enumeration, USB permission request
  (`UsbManager.requestPermission` + the standard broadcast-receiver wait), and the three AOAP
  control transfers in order - `GetProtocol` (0x33), `SendString` x6 (0x34, identifying this app as
  manufacturer "Android" / model "Android Auto", matching AOAP's own public spec convention), and
  `StartAccessory` (0x35). All implemented directly against Android's public USB host API - no
  native code.
- **Explicitly NOT implemented yet:** detecting and opening the phone once it re-enumerates as a
  USB accessory after `StartAccessory` (`ConnectionStage.ACCESSORY_REOPEN`); the SSL/TLS handshake
  over that accessory stream; auth; service discovery; opening the video channel; decoding a single
  frame; and the entire wireless bootstrap path (Bluetooth AA-Wireless service discovery + Wi-Fi
  credential exchange + hosting a local hotspot) - aasdk itself doesn't implement this last part
  either, it's outside that library's scope entirely, and remains the single hardest, least-
  precedented unknown in this whole direction.

**Per the project's own rule, repeated here deliberately: this is not a working Android Auto
receiver.** Nothing beyond `StartAccessory` has been attempted, let alone demonstrated, and even
the implemented steps are untested against a real phone as of this commit. The next on-car step is
simply: plug the phone in via USB, enable the toggle, press the negotiation button, and see how far
the real hardware actually gets - that result determines what gets built next, not an assumption.

## v0.26 CI failure and fix: vendored protos must stay flat

The first CI run after adding the Gradle protobuf plugin and the 96 vendored `.proto` files
(run `39909088879`, commit `d8068708`) failed at `:app:generateDebugProto`. The sandbox this
project is developed in cannot fetch the raw CI log directly - every attempt (`gh run view
--log-failed`, `gh run download`, `gh api .../actions/jobs/<id>/logs`) hits the same `Forbidden`
proxy block on the `results-receiver.actions.githubusercontent.com` / `*.blob.core.windows.net`
signed-URL endpoints GitHub Actions uses for full logs. The workflow's own error-annotation step
only grepped single matching lines ("FAILURE: Build failed...", "* What went wrong:") without the
line(s) immediately after, which is where Gradle actually states the cause - so the first failure
produced annotations with no real diagnostic value.

**Fix applied before diagnosing further:** widened `.github/workflows/build.yml`'s annotation step
to `grep -A 15` (grab 15 lines of context after each match) and added an unconditional "last 150
lines of build_output.log" dump on failure. Re-pushing with just that change (no app-code change)
surfaced the real error on the next CI run:

```
Execution failed for task ':app:generateDebugProto'.
> protoc: stdout: . stderr: AVStreamTypeEnum.proto: File not found.
AudioTypeEnum.proto: File not found.
AudioConfigData.proto: File not found.
VideoConfigData.proto: File not found.
aasdk/AVChannelData.proto:21:1: Import "AVStreamTypeEnum.proto" was not found or had errors.
aasdk/AVChannelData.proto:22:1: Import "AudioTypeEnum.proto" was not found or had errors.
```

**Root cause:** the vendored `.proto` files use same-directory-relative imports (e.g. `import
"AudioTypeEnum.proto";`, no path prefix) because in aasdk's own source tree they all live flat in
one `aasdk_proto/` directory. They had been vendored into a `app/src/main/proto/aasdk/` subfolder
for tidiness, but the Gradle protobuf plugin's default proto import root is `src/main/proto/`
itself - so every cross-file import failed to resolve once the files were one level deeper than
that root.

**Fix:** moved all 96 `.proto` files (and `NOTICE.md`) from `app/src/main/proto/aasdk/` up to
`app/src/main/proto/` directly - a pure file-move, zero content changes, so "vendored verbatim and
unmodified" still holds. `NOTICE.md` now explicitly warns against re-nesting them. Updated the
path comments in both `build.gradle` files accordingly. This is a pure build-plumbing fix; it does
not touch `ConnectionStage`/`AoapNegotiator`/`AndroidAutoReceiver` or any other v0.26 Java code, and
does not change what connection stages are implemented vs. not (still only as far as
`AOAP_START_ACCESSORY`, same as before - see the v0.26 section above).

## v0.27: button-event probe - a lead from a second reference app (SmartSplit)

A second real commercial app was analyzed for grounding, read-only, same method as DiPlay and the
factory AndroidAuto.apk before it: "SmartSplit" (`com.smartlink.smartsplit`), a licensed,
VIN-locked app described in its own manifest as "Vehicle dashboard overlay and split-screen
automation for Haval **H9**" - same GWM/Harman platform family as this H7, not a confirmed exact
match.

**Two findings, both grounded in the actual `classes.dex` strings, not guessed:**

1. It reads physical button events from a system property, `sys.other.keyevent_notify`, via
   reflection on the hidden `android.os.SystemProperties.get(String)` method, with a shell
   `getprop` fallback when reflection fails. Values are parsed with the regex
   `sys\.other\.keyevent_notify.*\[([0-9]+),\s*([0-9]+)\]` - i.e. button events are published as
   `[code, value]` pairs. No special permission is needed beyond hidden-API access - the same kind
   of reflective access this project already uses elsewhere for other hidden Car-service APIs.

2. It tries privileged input injection first (`InputManager.injectInputEvent` /
   `createInputForwarder`, both hidden methods) and its own log strings show that failing
   ("InputManager injectInputEvent method is unavailable") on real hardware, falling back to
   `AccessibilityService`-based synthetic taps/gestures (`ClusterAccessibilityService`) for actual
   on-cluster interaction - a standard, non-privileged permission, not root/signature. One of its
   log lines ("Accessibility overlay has no display-2 token; trying app overlay") shows it hits
   the same "how do you draw on display 2" problem this project solved with
   `ActivityOptions.setLaunchDisplayId` - confirming that's a genuinely awkward part of this
   platform for everyone, not just us.

Nothing in SmartSplit's strings touches `car.ipk_setting.theme_config` or anything resembling the
hut-code/vehicle-coding backup - it only reads ordinary vehicle signal properties
(`car.basic.steering_wheel_angle`, `car.ipk_light.fuel_low_lmpsts`, `persist.gwm.vehicle.vin`).

**What v0.27 adds:** `ButtonEventProbe.java` - a read-only diagnostic, same start/stop/background-
thread pattern as `ClusterStateBroadcastListener`/`NavigationRendererProbe`. Polls
`sys.other.keyevent_notify` every 300ms (reflection first, `getprop` shell fallback second, same
two paths SmartSplit itself uses), logs the initial value once and then only logs again on change.
No input injection, no synthetic taps, nothing written anywhere - this only reads. **Not yet
confirmed working on this H7** - SmartSplit's target is the H9, and this property may not exist,
or may behave differently, here. The real test is pressing a steering-wheel/cluster button on the
car while this probe is running and watching for a logged change.

**Why this matters beyond diagnostics:** the user's stated longer-term goals are (a) eventually
giving the cluster overlay real button-driven control (e.g. switching what's shown on Display 2
once the Android Auto receiver is actually projecting a map there), and (b) a possible future
CarPlay/CarPlay Ultra receiver alongside the Android Auto one. A working, non-privileged way to
read physical button events would be useful groundwork for both - but input *injection*/synthetic
interaction is explicitly out of scope for this increment, and nothing here claims button control
works, only that button *reading* is being probed.

## v0.28: first real on-car success - USB/AOAP handshake confirmed working, ACCESSORY_REOPEN added

On-car logs (2026-10-23, two separate runs) showed, for the first time in this project, a real
phone completing every USB/AOAP stage this app implements: `USB_DEVICE_ENUMERATION`,
`USB_PERMISSION`, `AOAP_PROTOCOL_QUERY` (phone answered "AOAP protocol version 2 supported"),
`AOAP_SEND_IDENTIFYING_STRINGS` ("All 6 identifying strings accepted"), and `AOAP_START_ACCESSORY`
("StartAccessory sent (result=0)") all reached `TESTED_WORKING` against a real Samsung phone
(`vendorId=0x18d1 productId=0x2d01`) on the actual car. This is real, not simulated - the control
transfers genuinely round-tripped over USB to the phone and back.

**One thing the logs also showed that needed fixing:** in both runs the phone was already in
Google's accessory mode (`vendorId=0x18d1`, Google's vendor ID - not a Samsung vendor ID) by the
time the probe ran, meaning it stayed in accessory mode across separate app runs rather than
reverting back to being a normal phone. v0.27's code didn't account for this - it re-sent the full
GetProtocol/SendString/StartAccessory handshake to a device that had already completed it. That
happened to return success harmlessly, but re-sending AOAP's own setup handshake to an
already-switched-over accessory interface isn't meaningful protocol behaviour and isn't something
to rely on.

**What v0.28 adds:**
- `AoapNegotiator.isAccessoryDevice(UsbDevice)` - detects Google's vendor ID (0x18D1) plus any of
  the documented accessory-mode product IDs (0x2D00-0x2D05).
- `AndroidAutoReceiver.negotiateWithDevice` now checks this FIRST: a device already in accessory
  mode skips the handshake entirely and goes straight to `ConnectionStage.ACCESSORY_REOPEN`.
- A real `ACCESSORY_REOPEN` implementation: `AoapNegotiator.openAccessoryEndpoints` claims the
  device's vendor-specific USB interface and finds its bulk IN/OUT endpoints - the real mechanism
  for talking to an AOAP accessory as the HOST side (ClusterProbe here is the host; the phone is
  the accessory). This is deliberately NOT `UsbManager.openAccessory`/`UsbAccessory` - that API is
  for the opposite role (an Android app that IS the accessory being opened by someone else), which
  doesn't apply here. `ConnectionStage.ACCESSORY_REOPEN`'s description was corrected to say this
  explicitly, since the original v0.26 description was wrong about which API applies.
- For a device NOT yet in accessory mode: after `StartAccessory` succeeds, the app now
  automatically listens for `UsbManager.ACTION_USB_DEVICE_ATTACHED` for up to 8 seconds (the old
  `UsbDevice`/`UsbDeviceConnection` are dead the instant the phone actually disconnects to
  re-enumerate), and opens whatever newly-attached device matches `isAccessoryDevice`. If nothing
  shows up in that window, it falls back to one direct poll of the current device list before
  giving up - and still tells the user that pressing "Run" again has reliably found the phone
  already in accessory mode on this car even when the automatic wait misses the exact moment.

**What v0.28 explicitly does NOT do:** read or write anything on the claimed bulk endpoints. That
starts at `ConnectionStage.SSL_HANDSHAKE` - the actual Android Auto TLS session over those
endpoints - which remains entirely unimplemented. On success, `openAccessoryStage` deliberately
leaves the `UsbDeviceConnection` open (not closed) since a future SSL-handshake step will need it;
nothing currently uses that open connection for anything. No Android Auto session exists yet, no
video, nothing beyond "a bulk-capable accessory interface was found and claimed" - consistent with
this project's standing rule not to claim a working receiver until a real phone connection and
video frame have actually been demonstrated.

**Next real milestone:** `ConnectionStage.SSL_HANDSHAKE` - a TLS handshake run manually over the
raw bulk IN/OUT byte stream rather than a normal `Socket`/`SSLSocket` (there is no socket here,
just two USB bulk endpoints), almost certainly via `javax.net.ssl.SSLEngine`'s non-blocking,
buffer-based API, which is designed for exactly this kind of non-socket transport. This is real,
substantially harder work than everything implemented so far, and is the next increment.

## v0.29: real AA message framing + VersionRequest/VersionResponse exchange

With `ACCESSORY_REOPEN` confirmed `TESTED_WORKING` on-car (see the v0.28 section above - a full,
clean, fully-automatic run with real phone, real handshake, real re-enumeration, permission grant,
interface claim and bulk endpoints all actually happened), the next real piece of work is the
Android Auto message framing itself, before `SSL_HANDSHAKE` can mean anything (even the TLS
handshake's own bytes travel wrapped in this framing, as an `SSL_HANDSHAKE` (0x0003) message).

**The frame format was read directly from aasdk's real source** (not guessed, not copied as code -
reimplemented in Java against what the C++ documents), specifically `Messenger/FrameHeader.{hpp,
cpp}`, `Messenger/FrameSize.{hpp,cpp}`, `Messenger/EncryptionType.hpp`, `Messenger/MessageType.hpp`,
`Messenger/ChannelId.hpp`, and `Messenger/MessageOutStream.cpp` (specifically to confirm SHORT vs.
EXTENDED framing is chosen by frame type, not guessed): a 2-byte header (channel ID + a flags byte
combining frame type/encryption/message-type bits) followed by a 2-byte (SHORT) or 6-byte
(EXTENDED, multi-frame only) big-endian size field, followed by the payload - whose own first 2
bytes are a big-endian message ID (from the already-vendored `ControlMessageIdsEnum.proto`).
`Channel/Control/ControlServiceChannel.cpp` confirmed the exact fields needed for `VersionRequest`/
`VersionResponse` (both PLAIN, not protobuf-encoded - a quirk of the real protocol: just 4 raw
bytes, major+minor as big-endian uint16s) and settled an ambiguity in `ConnectionStage`'s
description from v0.26: every real control-channel message, even ones on the CONTROL channel, uses
`MessageType.SPECIFIC`, not `MessageType.CONTROL` - that flag is something else aasdk's own code
never sets from this file.

**What v0.29 adds:**
- `AaProtocol.java` - the frame/message read/write layer over the bulk IN/OUT endpoints
  `ACCESSORY_REOPEN` already claims. Only single-frame (`BULK`, i.e. `FIRST|LAST`) messages are
  implemented - true multi-frame (EXTENDED-size, `MIDDLE`/`LAST` continuation) messages are
  detected and reported cleanly, not silently mishandled, since every message this version sends
  or expects is small enough to fit in one frame regardless.
- A new `ConnectionStage.VERSION_EXCHANGE`, between `ACCESSORY_REOPEN` and `SSL_HANDSHAKE`: sends
  a `VersionRequest` (declaring v1.1 - the same version aasdk itself declares, a real,
  widely-interoperable value), reads back a `VersionResponse`, and logs the phone's reported
  version and MATCH/MISMATCH status. This is a genuine, independently meaningful on-car test of
  the new framing layer, in plaintext, lower-risk than and a prerequisite for the TLS work after
  it.

**What v0.29 explicitly does NOT do:** anything with TLS/SSL. The real fixed certificate/private
key pair aasdk's `Cryptor.cpp` uses (labeled "Google Automotive Link"/"JVC Kenwood" in the cert's
own subject, GPLv3, same provenance as the vendored `.proto` files - every open-source AA head
unit implementation uses this exact same pair, since the protocol's TLS layer doesn't validate
against a real CA here) has been found and confirmed in the real source, but is not vendored or
used yet - that starts with `ConnectionStage.SSL_HANDSHAKE` itself, the next increment, which also
needs `javax.net.ssl.SSLEngine`'s non-blocking buffer-based API (there's no `Socket` here, just two
USB bulk endpoints) - genuinely the hardest piece of work in this project so far.

**On-car test needed next:** run the AA receiver negotiation as before; if `ACCESSORY_REOPEN`
succeeds, watch for the new `VersionRequest -> ... VersionResponse -> phone reports vX.Y,
status=...` log lines. A real version number and MATCH status from the phone would be the first
confirmation that two-way message exchange over the claimed bulk endpoints actually works, before
any encryption is involved.

## v0.30: VersionRequest write confirmed on-car; fixing the read path

On-car logs (2026-10-23) ran `VERSION_EXCHANGE` for the first time. Two outcomes:

- A stale, already-accessory device from a prior run: the `VersionRequest` write itself failed
  immediately (`bulkTransfer (write) sent -1 of 10 bytes`) - the claimed interface from an old
  session wasn't actually usable for I/O (not investigated further - a fresh run avoids this).
- A fresh, full handshake (real AOAP negotiation -> automatic re-enumeration -> permission granted
  -> interface claimed): **the `VersionRequest` write succeeded** - `sent messageId=0x1, 10 bytes
  total (4-byte body)`. This is the first real data this project has ever transmitted to the phone
  over the Android Auto channel itself, not just the USB/AOAP setup layer. But reading the
  `VersionResponse` back timed out: `bulkTransfer (read) returned -1 after 0/4 bytes`.

A write succeeding while a read on the same endpoint pair reliably times out points at something
specific, not a protocol-level encoding bug: Android's synchronous `UsbDeviceConnection.
bulkTransfer()` for *reads* has well-documented reliability problems on several SoCs, Qualcomm
platforms (this head unit's SA8155) among them. Community Android-Auto-host implementations
generally use the asynchronous `UsbRequest`/`requestWait()` API for bulk reads specifically
because of this.

**What v0.30 changes:** `AaProtocol.readExactly` now uses `UsbRequest.queue(ByteBuffer)` +
`UsbDeviceConnection.requestWait(long)` instead of synchronous `bulkTransfer()` for reads (writes
are unchanged - they already worked). Read timeout also raised from 3s to 6s, in case the phone
simply needs longer before its first response. **This is a plausible, well-precedented fix, not a
certain one** - the real cause could still be something else (a subtly wrong `VersionRequest`
encoding that the phone silently ignores, a missing step before `VersionRequest` is valid, etc.).
The next on-car run is what actually tells us.

Also worth recording: during this test the car's screen fell back to the factory GWM Android Auto
experience after our receiver's negotiation stalled. This is expected, not a bug - this project's
receiver has never touched or disabled GWM's factory receiver, and our side doesn't yet complete a
real session (it deliberately stops the moment `VERSION_EXCHANGE` fails), so the phone falls back
to whatever can actually finish a handshake. Same "swap not tap" behaviour already confirmed with
Head Unit Revived on this car - will keep happening on every test run until this receiver actually
completes a full session, not something to fix separately.

## v0.31: read path narrowed further, then work paused to investigate wireless instead

On-car logs (2026-10-23) after v0.30's switch to async `UsbRequest` reads: the hard timeout is
gone (`requestWait()` genuinely completes now) but on two separate fresh runs it completed with
**0 bytes** - a different, more specific symptom than before. `AaProtocol.readMessage` was
rewritten to read into one generous single buffer (512 bytes) with up to 3 retries on a 0-byte
completion, rather than two separate precise reads (4-byte header, then N-byte payload) - splitting
reads like that is a known pitfall if the phone sends its whole reply as one USB transfer. Also
removed the now-dead `readExactly` helper. **This has not been tested on-car yet** - it's an
untested, plausible next attempt, not a confirmed fix, same honesty bar as v0.30's change.

**Work on the USB path is paused here, at the user's explicit direction, to investigate the
wireless (Wi-Fi) bootstrap path instead** - not because the USB approach stopped being promising
(it's arguably closer to a real result than anything in this project so far: AOAP handshake,
interface/endpoint claim, and the VersionRequest *write* have all been confirmed working against
real hardware) but because the user wants the physical convenience of not needing a USB cable for
each test cycle, having weighed the trade-off explicitly (recorded here for continuity): the
wireless bootstrap (Bluetooth AA-Wireless discovery/pairing, Wi-Fi credential exchange, this head
unit hosting its own Wi-Fi hotspot, then a TCP session over that link) is a separate, harder,
currently entirely unstarted problem - aasdk itself doesn't implement it at all. The actual Android
Auto message/protocol layer being built here (`AaProtocol`, `VersionRequest`/`Response`, and
`SSL_HANDSHAKE` once built) is transport-agnostic and will be reused by whichever transport
(USB or wireless) actually gets a working session first - aasdk itself shares this same layer
between its `USBTransport` and `TCPTransport`. Nothing done on USB so far is wasted by this pivot.

## v0.32: wireless bootstrap - real, sourced sequence found and implemented (untested on-car)

Research continued from where the above left off, using `aa-proxy/aa-proxy-rs`
(https://github.com/aa-proxy/aa-proxy-rs, commit `5a6887d427f4b6f6395bf1d3d7d4d31f805e4f98`,
GPLv2) - a real, actively-maintained open-source project that *also* acts as the head unit role in
Android Auto Wireless (not just a proxy/MITM between a real head unit and a phone - it has its own
working `send_params()` function doing exactly what ClusterProbe needs). Read directly from its
`src/bluetooth.rs` and `src/protos/*.proto`, not guessed:

- **The real, fixed Android-Auto-Wireless Bluetooth RFCOMM service UUID**:
  `4de17a00-52cb-11e6-bdf4-0800200c9a66` (`AAWG_PROFILE_UUID` in aa-proxy-rs). Every phone's AA app
  looks for an SDP record under exactly this UUID when attempting a wireless connection to a head
  unit it has already Bluetooth-paired with the normal way.
- **The real bootstrap message exchange**, confirmed from `send_params()` (the actual head-unit-role
  function, not the MITM-relay code elsewhere in that file):
  1. HU -> phone: `WifiStartRequest { ip_address, port }` - where the phone should open the real
     Android Auto TCP session once it has joined the HU's Wi-Fi AP.
  2. phone -> HU: `WifiInfoRequest` (empty body).
  3. HU -> phone: `WifiInfoResponse { ssid, key, bssid, security_mode=WPA2_PERSONAL,
     access_point_type=DYNAMIC }` - the HU's own hosted AP's credentials.
  4. phone -> HU: `WifiStartResponse` (body has a documented but, even in aa-proxy-rs's own code,
     unparsed status field - discarded by length, not decoded).
  5. phone -> HU: `WifiConnectStatus` - aa-proxy-rs's own code checks only `buf[1] != 0`
     (`[0x08, 0x00]` observed for success; a longer varint encoding of a large negative int64 for
     failure). Reproduced exactly, not guessed at further.
  6. Phone then opens a plain TCP connection to the `ip:port` from step 1, over the Wi-Fi network it
     just joined - and from that point on it's the **same** AA Messenger protocol `AaProtocol`
     already implements for USB (confirmed: aasdk itself shares one protocol layer between its
     `USBTransport` and `TCPTransport` - this was not a guess, it's how the real spec is structured).
- **Wire framing for this bootstrap channel only** (distinct from `AaProtocol`'s Messenger framing,
  which still applies, unchanged, to everything after step 6): a flat 4-byte header - 2-byte
  big-endian payload length + 2-byte big-endian message ID - then the raw payload. Read directly
  from aa-proxy-rs's own `send_message()`/`read_message()` functions.
- **The real `.proto` field layouts** for the two messages that actually carry data
  (`WifiStartRequest`: `ip_address` string field 1, `port` int32 field 2; `WifiInfoResponse`: `ssid`
  string 1, `key` string 2, `bssid` string 3, `security_mode` enum 4, `access_point_type` enum 5) -
  vendored as field-layout copies (renamed to `AaWirelessStartRequest`/`AaWirelessInfoResponse` to
  avoid any ambiguity with Android's own Wi-Fi classes) into `app/src/main/proto/`, same pattern as
  the aasdk `.proto` files. See `app/src/main/proto/NOTICE.md` for full sourcing/licensing.
- **How the HU side hosts its own Wi-Fi AP**: aa-proxy-rs does this with `hostapd`+`dnsmasq` on
  Linux - not directly portable to Android. The Android-correct equivalent used here is the public,
  unprivileged `WifiManager.startLocalOnlyHotspot()` API (added API 26, available on this head
  unit's API 28) - `AaWirelessHotspot.java`. Needs `ACCESS_FINE_LOCATION` granted at runtime (a
  platform requirement for hotspot credentials, treated like Wi-Fi scan results).

**What was built this version** (new, disabled-by-default, same `AndroidAutoReceiver.enabled`
master switch the USB path uses - one feature, two transports):
- `AaWirelessConstants.java` - the UUID, message-ID constants, and the 4-byte bootstrap framing
  read/write helpers.
- `AaWirelessHotspot.java` - wraps `WifiManager.startLocalOnlyHotspot()`.
- `AaWirelessBootstrapServer.java` - orchestrates the whole sequence: start hotspot -> open a TCP
  `ServerSocket` (OS-assigned port) for the eventual AA session -> advertise + accept one Bluetooth
  RFCOMM connection on the AAWG UUID -> run the bootstrap exchange -> accept the phone's resulting
  TCP connection. Reports progress through the same `ConnectionStage.WIRELESS_BOOTSTRAP` stage (one
  box, not many sub-stages, since unlike the USB path this is a single Bluetooth-then-Wi-Fi
  handshake rather than several independently-testable hardware-protocol layers) via the existing
  `AndroidAutoReceiver.StatusListener`/`StageStatus` types - no new status machinery needed.
- New manifest permissions: `BLUETOOTH`, `BLUETOOTH_ADMIN` (legacy, this device's targetSdk 28 does
  not need the API 31+ split permissions), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
  `ACCESS_FINE_LOCATION`. Does **not** touch Bluetooth discovery/pairing - the phone must already be
  paired with this head unit the ordinary way; this only advertises one RFCOMM service and hosts a
  local AP, both ordinary non-privileged app capabilities, consistent with every other boundary this
  project has kept (never touching the signature-protected GWM interfaces, never interfering with
  the factory GWM AA receiver or its pairing).
- MainActivity: a new "Wireless AA bootstrap" section, gated behind the same master toggle plus a
  runtime `ACCESS_FINE_LOCATION` request, with Run/Stop buttons mirroring the USB section's.

**Deliberately stops at "phone's TCP connection accepted"** for this increment, then closes it and
reports `TESTED_WORKING`/`TESTED_FAILING` - it is **not yet wired into `AaProtocol`'s
VersionRequest/VersionResponse exchange**. `AaProtocol`'s read/write methods are currently written
directly against `UsbDeviceConnection`/`UsbEndpoint`; generalizing them to also accept a plain
`Socket`'s `InputStream`/`OutputStream` is a small, low-risk follow-up best done once this bootstrap
itself has been confirmed working on real hardware - same build-in-stages approach used throughout
this project (ACCESSORY_REOPEN before VERSION_EXCHANGE, etc.).

**Status: built, compiles clean (stub harness), pushed, CI green. NOT yet tested on-car.** Per the
project's standing rule, this is not described as working wireless AA - only as a real, sourced
implementation of the bootstrap handshake, awaiting its first real test: phone already paired over
Bluetooth, master toggle ON, location permission granted, then "Run wireless AA bootstrap" pressed
while the phone's Android Auto app is told to connect wirelessly.

## v0.33: wireless bootstrap first on-car result - hotspot refused, ERROR_INCOMPATIBLE_MODE

First real on-car test of v0.32's wireless bootstrap: `WifiManager.startLocalOnlyHotspot()`
immediately failed with `LocalOnlyHotspotCallback.onFailed reason=3`
(`ERROR_INCOMPATIBLE_MODE`), before anything Bluetooth-related even started.

`ERROR_INCOMPATIBLE_MODE` is AOSP's own code for "Wi-Fi's current mode/state won't allow a local
AP to start." On ordinary phones this is rare; on automotive Wi-Fi chipsets/OEM builds it's a
known class of problem - either no supported STA+AP concurrency (can't run client mode and AP
mode at once), or the vendor disabled softAP capability outright for the public API. This device's
own evidence points at the latter: the earlier static analysis of DiPlay (the factory-adjacent AA
app) found a native library called `liblocal_hotspot_radio.so` - GWM apparently built their own
private hotspot mechanism rather than calling `WifiManager.startLocalOnlyHotspot()` the normal way,
which is consistent with that public API simply not working on this hardware/build at all.

Added `decodeHotspotFailureReason()` (`AaWirelessHotspot.java`) to turn any future reason code into
a readable explanation instead of a bare number, and a non-terminal `Listener.onDiagnostic()`
callback that logs the actual pre-attempt Wi-Fi state (enabled? already STA-connected? to what
SSID?) - separate from `onFailed()` so it can never short-circuit the hotspot-start flow itself.
Both ship disabled-by-default, same as everything else in this path - diagnostic improvements only,
no behavioural change to what's attempted yet, pending the next on-car run's diagnostic output and
a decision on how to proceed (see below).

## v0.34: OpenAuto source inspected - a much simpler wireless path found

User supplied the real OpenAuto source (f1xpl/openauto, GPLv3, same author as aasdk, built for
Linux/RaspberryPi - C++/Qt/Boost, not portable to Android/Java as-is, and not copied here; read
only as a reference spec, same approach already used for aasdk and aa-proxy-rs).

Key finding, from `src/autoapp/UI/ConnectDialog.cpp`: OpenAuto's own "wireless" mode is nothing
like the Bluetooth-RFCOMM-plus-hotspot bootstrap built in v0.32 - it's a **plain TCP client
connection to a user-typed IP address on port 5277**. No Bluetooth, no AP hosting on the head
unit's side at all. The README explains why: "Wireless (WiFi) mode via head unit server (must be
enabled in hidden developer settings)" - a real, well-documented hidden option in the phone's own
Android Auto app (tap the version number repeatedly in Android Auto's own Settings to unlock
developer settings, then enable "Start head unit server"). In that mode **the phone becomes the
TCP server** and shows its own IP; the head unit just connects out to it as an ordinary client.
Port 5277 matches aa-proxy-rs's own `TCP_DHU_PORT` constant ("Desktop Head Unit") - Google's own
real port for this, cross-confirming it rather than guessing.

This sidesteps the v0.33 `ERROR_INCOMPATIBLE_MODE` hotspot dead-end entirely: neither device needs
to host anything, they just both need to already be on the same Wi-Fi network (home/mobile Wi-Fi,
or any hotspot either is already running for an unrelated reason). Far simpler to implement and
test, and doesn't depend on this head unit's Wi-Fi chipset/OEM build supporting AP mode at all.

Added `AaWirelessManualConnect.java` - opens a plain `Socket` to a typed IP:5277, reports success/
failure through the same `ConnectionStage.WIRELESS_BOOTSTRAP` stage. New MainActivity section with
an IP text field and Connect/Stop buttons, same master-toggle gate as every other AA path. Kept
`AaWirelessBootstrapServer` (the Bluetooth+hotspot path) as-is, untouched - not deleted, since it
may still be useful later (e.g. if a workaround for the hotspot issue turns up, or for a fully
automatic connection without typing an IP by hand) - this is an additional, simpler option, not a
replacement.

**Status: built, compiles clean, pushed, CI green. Not yet tested on-car** (same evening the
hotspot test failed - next test is tomorrow). Like the Bluetooth path, this also stops at "TCP
connection confirmed" and is not yet wired into `AaProtocol`'s VersionRequest exchange - same
deferred next step either way.

## v0.35: a third wireless mechanism found - Wi-Fi Direct feasibility probe

User supplied two more real, actively-developed Android-native (unlike OpenAuto - same platform
as ClusterProbe) Android Auto receiver projects:
- `headunit-borconi-master` ("Headunit Reloaded", AGPLv3) - its real "P2PWifi" mode
  (`hu_act.java`'s `wifi_init()`/`wifi_start(4)`) uses `android.net.wifi.p2p.WifiP2pManager`
  directly, connecting to a hardcoded `192.168.49.122` after `createGroup()` - confirming
  `192.168.49.1` as the real, standard Wi-Fi-Direct group-owner address.
- `open-headunit-main` (current, 2026/Android-Auto-17.4-era fork) - its README lists five real
  wireless options side by side: **1. USB wireless dongle, 2. Native Mode (Wi-Fi Direct or
  Headunit Hotspot handshake), 3. Headunit Server (Developer Mode, no root) - exactly the
  manual-IP approach already built in v0.34, 4. Wireless Helper (legacy, blocked on AA 17.4+
  without root), 5. a root workaround.** Its `WifiDirectManager.kt` (3400+ lines) is a heavily
  hardened real implementation of option 2, including a `BUSY` (reason=2) retry pattern and
  `192.168.49.1` GO-address fallback logic.

This is valuable for two reasons: it independently confirms v0.34's manual-IP "Headunit Server"
approach is the right thing to have tried first (open-headunit's own docs call it out as the
current non-root recommendation given AA 17.4+ blocks the older automatic triggers) - and it
surfaces a real alternative to the hotspot API that failed in v0.33.
`WifiManager.startLocalOnlyHotspot()` (which failed with `ERROR_INCOMPATIBLE_MODE`) and
`WifiP2pManager` (Wi-Fi Direct) are different Android APIs with different underlying driver
requirements - Wi-Fi Direct is a long-standing Android CTS baseline requirement, so there's a real
chance this automotive Wi-Fi chipset supports it even though the softAP path didn't.

Added `AaWirelessDirectProbe.java` - deliberately minimal (not a port of open-headunit's 3000+
line hardened manager, just a feasibility check): calls `WifiP2pManager.createGroup()` with a
BUSY-retry, decodes the real AOSP failure-reason codes, and on success scans for the group-owner's
`192.168.49.x` interface address. Stops there - does NOT attempt the phone-side join/handshake,
which both reference projects show is a substantial, further research question of its own (not
simply an SSID/passphrase exchange in every Android version). New MainActivity section with
Run/Stop buttons. No new manifest permissions needed - `CHANGE_WIFI_STATE` and
`ACCESS_FINE_LOCATION` (already declared for the hotspot path) cover `WifiP2pManager` too.

**Status: built, compiles clean, pushed, CI green. Not yet tested on-car.** Three wireless options
now exist side by side for tomorrow's test session: Bluetooth+hotspot bootstrap (v0.32, likely
blocked by the same `ERROR_INCOMPATIBLE_MODE` issue), manual-IP Headunit Server connect (v0.34,
best bet to work first time), and this Wi-Fi Direct feasibility probe (v0.35, cheap to test,
answers whether a fuller Native Mode implementation is worth building next).

## v0.36: the real AA protocol pipeline - version/TLS/discovery/video-channel setup, through to a visible decode surface

Per the explicit instruction to stop shipping disconnected probes and "build the view into it" -
this increment builds the actual Android Auto session protocol, transport-agnostic (same code path
for USB or any wireless transport, same split aasdk itself uses between its USBTransport and
TCPTransport), through to real decoded H.264 frames rendered on screen. It does NOT yet include
any on-car test - see Status below.

**Correcting message directionality.** Re-read aasdk's actual `ControlServiceChannel.cpp` and
`SSLWrapper.cpp`/`Cryptor.cpp` source directly (not re-derived from memory) before writing any new
protocol code, because an earlier pass through this project had assumed the wrong sender/receiver
roles for some control messages. Confirmed from source: as the head unit, THIS side sends
`VersionRequest`, `Handshake`, `AuthCompleteIndication` (sent **unencrypted** - before the TLS
session is used for anything else), `ServiceDiscoveryResponse` (encrypted), and
`ChannelOpenResponse`/`AVChannelSetupResponse` (encrypted, per-channel) - and receives
`VersionResponse`, `ServiceDiscoveryRequest`, `ChannelOpenRequest`, `AVChannelSetupRequest`. Also
confirmed: the head unit is the **TLS client** (`SSL_set_connect_state()`), presents a fixed,
publicly-known client certificate/key (same one every open-source AA head unit implementation
uses - not something ClusterProbe invented), and does **not** validate the phone's server
certificate at all (`SSL_VERIFY_NONE`).

**New files:**
- `MiniProto.java` - a minimal hand-rolled protobuf wire-format reader/writer (varint and
  length-delimited fields, nested messages) used instead of Gradle-generated protobuf-java
  classes, for the same reason `AaWirelessConstants` hand-rolled the v0.32 bootstrap messages:
  there's no protoc/real Android SDK available for local pre-push verification in this project's
  tooling, so generated classes can't be sanity-checked before a push.
- `AaControlMessages.java` - builds/parses `AuthCompleteIndication`, `ServiceDiscoveryRequest`,
  `ServiceDiscoveryResponse` (advertising exactly one video channel - 480p@30fps, dpi=140 - with
  `head_unit_name="ClusterProbe"`, `car_model="Haval H7 Vanta Hybrid"`), `ChannelOpenRequest`/
  `ChannelOpenResponse`, and `AVChannelSetupRequest`/`AVChannelSetupResponse` - field layouts taken
  directly from the real vendored `.proto` files already in this repo
  (`app/src/main/proto/*.proto`, sourced from aasdk per the existing `NOTICE.md`).
- `AaTlsSession.java` - a real `SSLEngine`-based TLS client handshake using the vendored fixed
  cert/key. The PKCS1-to-PKCS8 private-key conversion and the full
  cert-parse -> key-parse -> KeyStore -> KeyManagerFactory -> SSLContext -> SSLEngine pipeline was
  written and verified standalone against the real JDK `javax.net.ssl`/`java.security` stack
  (identical APIs to Android) BEFORE being transcribed into this file - that standalone test caught
  and fixed a `BUFFER_OVERFLOW` bug (a flat 16KB output buffer was too small for a real TLS record;
  fixed by sizing via `SSLSession.getPacketBufferSize()`) that would otherwise have shipped
  untested.
- `AaSessionRunner.java` - orchestrates the whole exchange over a connected `Socket`: VersionRequest
  -> VersionResponse -> TLS handshake -> AuthCompleteIndication -> ServiceDiscoveryRequest ->
  ServiceDiscoveryResponse -> a receive loop that answers `ChannelOpenRequest`/
  `AVChannelSetupRequest` on the video channel and extracts raw H.264 bytes from
  `AV_MEDIA_(WITH_TIMESTAMP_)INDICATION` frames (stripping the 8-byte timestamp prefix when
  present), handing them to a listener. Deliberately UI-free so it's testable independent of any
  Activity.
- `AndroidAutoSessionActivity.java` - the actual visible window this instruction asked for: a
  `SurfaceView` + status `TextView`, running `AaSessionRunner` on a background thread and decoding
  whatever H.264 bytes arrive with a real `android.media.MediaCodec` (`video/avc`, default
  800x480 "480p"-class format matching what's advertised in ServiceDiscoveryResponse), rendering
  straight to the Surface via `releaseOutputBuffer(index, true)`. Runs on the default display for
  now, deliberately - per the user's own stated roadmap ("connect first and see Android Auto, THEN
  project to Display 2, THEN build the dash cluster on top"), routing this to Display 2 is the next
  phase, not this one.

**Modified:**
- `AaProtocol.java` - generalized its USB-only frame I/O with Socket-based `writeFrame`/`readFrame`
  (`SocketMessage` carries channelId/messageId/body/encrypted), so the same framing works over any
  transport. USB-specific methods untouched.
- `AaWirelessManualConnect.java` - the v0.34 manual-IP ("Headunit Server") path no longer closes its
  TCP connection as a test; a successful connect now hands the live `Socket` straight to
  `AndroidAutoSessionActivity` (via a static `pendingSocket` field, since a connected `Socket` can't
  go in an `Intent` extra) and launches it. This is the transport most likely to work first per the
  user's own next test, so it's the one wired all the way through in this increment; the Bluetooth
  bootstrap (v0.32) and Wi-Fi Direct probe (v0.35) paths are not yet wired to this session code.
- `AndroidManifest.xml` - registered `AndroidAutoSessionActivity` (exported=false, fullscreen
  black theme, resizeable).

**Verification done before pushing:** full stub-compile pass (via this project's established
`/tmp`-based plain-javac harness, extended with `MediaCodec`/`MediaFormat`/`SurfaceView`/
`SurfaceHolder`/`FrameLayout`/`TextView`/`Color`/`Log` stand-ins) across every new and modified
file in this increment. This caught two real bugs before they reached CI or the car: (1) a stray
`*/` inside a Javadoc comment in `AaControlMessages.java` that would have prematurely closed the
comment block and failed compilation outright; (2) a missing `context` parameter threaded through
`AaWirelessManualConnect.doConnect()`, needed for the new `startActivity()` call, that the compiler
caught as an unresolved symbol. Both fixed; the harness reports a clean exit after the fixes.

**Status: NOT yet tested on real hardware, and not yet CI-verified at the time this section was
written (CI run is the next step after this commit).** This is protocol-layer code following the
same evidence-based practice as every other increment in this project - real message IDs, real
field layouts, a real verified TLS pipeline - but "compiles clean against stubs" is not the same
claim as "a phone has connected and a frame has rendered." No Android Auto video has been displayed
by this app yet. The next real test is the user connecting a phone via the v0.34 manual-IP path
(enabling "Start head unit server" in the phone's Android Auto developer settings) and watching
whether `AndroidAutoSessionActivity` actually opens and reaches "FIRST VIDEO FRAME DECODED" - that
status string is the actual bar for "Android Auto is visible," not CI passing.

## v0.36 addendum: real confirmation from a known-working app on this exact head unit

User supplied `Headunit_Reloaded_vV8.2_.0_Patched.apk` - a confirmed-working app ("used this on my
headunit with no issues") - not the old AGPLv3 `headunit-borconi` Java source inspected for v0.34/
v0.35 (different codebase entirely: this APK is a modern Kotlin/Compose app with native `libusb`
and a small native helper lib, `libhur.so`). Per this project's "read as spec only" rule, this was
inspected as a compiled black box (manifest/dex strings only, via `unzip`+`strings` - no
decompilation of logic, nothing transcribed) purely to confirm real-world behavior on this exact
car, not to source code from it.

**What the strings confirm, in priority order the app itself appears to use:**
1. **Wi-Fi Direct (`WifiP2pManager`) is the primary/preferred mechanism** - the same API this
   project's v0.35 `AaWirelessDirectProbe` exercises, using the same real UUID
   (`4de17a00-52cb-11e6-bdf4-0800200c9a66`) and the same `192.168.49.1` group-owner address this
   project already found and used.
2. **`LocalOnlyHotspot` is an explicit fallback, not the primary path** - string
   `"WiFi-P2P failed - falling back to LocalOnlyHotspot (video will cap to 720p)"`. This is the
   mechanism that failed on-car in v0.33 with `ERROR_INCOMPATIBLE_MODE` - this app treats that
   exact failure as expected/recoverable, not fatal, and still has a usable path when it happens.
3. **A third, separate path strings as `"(>=17.4) - using developer Head-unit server path"`** -
   confirms, independently of open-headunit's README, that this app detects a modern phone-side AA
   version and switches to the same manual-IP "Head-unit server" / Developer Mode mechanism this
   project wired up in v0.34/v0.36 (`AaWirelessManualConnect` -> `AndroidAutoSessionActivity`).
4. A fourth internal mode strings as `"Phone mode: connected to Head-unit server on
   127.0.0.1:5277"` - the loopback/"Self Mode" case (same device acting as both head unit and
   phone, via Gearhead's own companion service) - not relevant to this project's two-device setup.

**Why this matters for what to test next, in order:** this is now real, independent confirmation
(not just inference from reference source) that this exact Haval H7 Vanta's Wi-Fi chipset refuses
`LocalOnlyHotspot` (matches v0.33's `ERROR_INCOMPATIBLE_MODE` exactly) but that a working app on
this same hardware still gets a connection - meaning either Wi-Fi Direct or the manual-IP
Head-unit-server path (or both) does work here. Testing priority stays as already wired: try the
manual-IP path first (`AaWirelessManualConnect`, already fully wired through to a rendered frame
as of this version) since it needs no car-side Wi-Fi hosting capability at all; if that's blocked
by the phone's own AA version, Wi-Fi Direct (v0.35's probe, not yet wired to the session pipeline)
is the next thing worth finishing, not the hotspot path - this patched app's own fallback order
agrees.

No code was changed in this addendum - documentation only.

## v0.36 addendum 2: DiPlay (wireless CarPlay) confirms CarPlay stays parked, now for a concrete reason

User supplied `DiPlay-0.2.21.apk` - a commercial CarPlay app ("shilapi.xcertplay") confirmed by the
user to work wirelessly with their work iPhone on this exact head unit. Inspected the same way as
the Headunit Reloaded APK above: packaging/dex strings only, as a black box - nothing decompiled
or transcribed, and this is a closed-source commercial app, not an open-source reference, so the
"read as spec only" rule applies even more strictly here than for aasdk/OpenAuto/headunit-borconi.

**What the strings show, at a protocol-choreography level (all public, documented CarPlay-wireless
knowledge - Bonjour/USBMUX/AirPlay/iAP2 - nothing new or secret in this part):** DiPlay tunnels
Apple's `usbmux`/`iAP2` accessory protocol over a TCP connection, negotiated and transported via an
AirPlay-style session (RTSP-like SETUP exchange, SRP6a key exchange, Bonjour/mDNS discovery) -
this is the standard, publicly-documented shape of wireless CarPlay used by every third-party
implementation. It also uses `WifiP2pManager` (same API this project already uses for Android
Auto) with its own hotspot-vs-P2P fallback tension (`wifi_direct_refused_beside_hotspot`),
consistent with what the Headunit Reloaded APK showed for Android Auto - this head unit's Wi-Fi
stack imposes the same restriction across both protocols, not something specific to either app.

**Why this doesn't reopen CarPlay as a buildable target:** every wireless CarPlay session has to
pass Apple's MFi ("Made for iPhone") authentication challenge before the phone will stream
anything - confirmed directly in these strings (`iap2 mfi rx=0xaa02 challenge`, `iap2 mfi tx=0xaa03
signature`). DiPlay satisfies that challenge through a **licensed MFi credential**, via one of two
backends it supports: a physical MFi authentication IC reachable over I2C (`libxcertplay_i2c.so`,
alongside a `CH341` USB-serial/I2C bridge) or a remote signing server operated by its vendor
(`/mfi/sign`, `mfi discovery backend=Remote server=`). The APK also bundles an offline fallback
credential at `assets/offline-mfi/identity.pk8` + `certificate.p7b`.

That credential is the vendor's own Apple-licensed MFi secret, not something this project has or
is entitled to use - extracting or repurposing it would be exactly the kind of signature/licensing
bypass this project's standing boundaries already rule out for the car's own protected systems,
except here it would also be taking a third party's licensed IP rather than the user's own
property. **No part of that credential was extracted, decoded, or examined beyond confirming its
file names/sizes exist**, and none of this project's code will attempt to use it.

**Conclusion: CarPlay stays parked - now because the real blocker is confirmed (a licensed MFi
identity this project has no legitimate way to obtain), not because of a missing protocol
investigation.** Everything else about wireless CarPlay's choreography (AirPlay/Bonjour/USBMUX) is
realistically reimplementable from public knowledge the same way Android Auto's protocol was - MFi
is the one piece that cannot be worked around by reading source or inspecting a binary, by design.
Android Auto remains the right near-term target: its equivalent "shared secret" (the fixed TLS
client cert/key already vendored into this project from aasdk) is public and intentionally shared
across every open-source head unit, with no equivalent per-vendor licensing gate.
