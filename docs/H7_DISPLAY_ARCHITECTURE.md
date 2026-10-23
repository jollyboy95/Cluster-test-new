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
