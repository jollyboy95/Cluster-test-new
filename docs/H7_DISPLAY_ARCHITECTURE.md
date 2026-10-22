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
