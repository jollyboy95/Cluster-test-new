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
