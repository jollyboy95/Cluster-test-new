# ClusterProbe (v0.3)

A test app for drawing your own dials/themes onto the H7's physical instrument-cluster display
(display 2, 1920x720) using only public Android APIs - no privileged/system permission is used or
attempted anywhere in this app.

## What's new in this version

- **Multiple selectable themes** (`DialView.Theme`):
  - `CREMA_GLASS` / `CREMA_SOLID` - the round speed+RPM dial pair, semi-transparent (glass) or
    opaque (solid) backgrounds. Nothing outside the two discs is drawn, so whatever is behind the
    overlay shows through there.
  - `APEX_WIDE` / `APEX_TIGHT` - a black mask covering the stock gauges, with a soft-edged
    transparent cut-out over the measured stock-map rectangle, plus live-drawn speed/RPM edge
    arcs. "Wide" and "tight" differ only in how much of the map rectangle is left uncovered.
- **Experimental map capture** (`CaptureHelper` + the "Test map capture" button in MainActivity):
  uses the public `MediaProjection` API to try capturing *some* display's live content and preview
  it in-app. There's no public API to request a specific displayId, so this will mirror whatever
  the platform hands back - the point of the button is to find out what that actually is on this
  head unit (a blank/black frame usually means the content is protected from capture). If it does
  pick up something useful, "Use captured frame in cluster" paints that into the Apex themes' map
  cut-out instead of leaving it transparent.

## What this app does NOT do

It never touches `car.ipk_setting.theme_config`, `GwmCarInformationManager`, or any
signature/privileged-protected interface. Forcing those from a normal app isn't something this
project attempts - see the project notes if you're wondering why.

## Using it

1. Build (`gradle assembleDebug`, or push to `main` and download the artifact from the GitHub
   Actions run) and install the debug APK.
2. Open the app, pick a theme with the Prev/Next buttons.
3. "Launch on cluster (display 2)" tries `ActivityOptions.setLaunchDisplayId(2)`. If it throws a
   `SecurityException`, that specific in-app launch path isn't available to a regular app here
   (the earlier `am start --display 2` from adb shell worked because the shell UID has more
   latitude) - the log will say so plainly rather than guessing.
4. "Stop cluster window" closes it again.
5. "Test map capture" asks for the system screen-capture permission and starts mirroring into the
   preview box - watch what actually shows up there.

## Known limitations

- Not compiled or run locally before delivery - build it via the included GitHub Actions workflow
  or Android Studio and report back anything that doesn't compile.
- The capture overlay is a straight bitmap paste into the mask's cut-out shape, not a live video
  stream synced frame-by-frame with low latency - treat it as a proof-of-concept, not a finished
  feature.
