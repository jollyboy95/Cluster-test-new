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
