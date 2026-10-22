# ClusterProbe as

Test app: tries three ways of drawing the Crema speed and RPM dials on another display (the cluster) and logs what the head unit allows.

Build without installing anything: make a free GitHub repo, upload this whole folder (keep the .github folder), open the Actions tab, run "Build debug APK", download the clusterprobe-apk artifact.
Or open the folder in Android Studio and run Build > Build APK.

Install and test:
  adb install -r app-debug.apk
  adb shell appops set com.clusterprobe SYSTEM_ALERT_WINDOW allow
  adb shell am start -n com.clusterprobe/.MainActivity
Then tap: 1 List displays, then methods A, B, C in turn. Photograph the cluster and screenshot the log.
Not compiled or run by the author: if the build fails, send the error log.

New in this version: the "Black mask" button covers the whole cluster with a black plate that has a soft see-through oval in the middle (so the Android Auto map shows there and the stock dials are hidden around it). The "Dials" button switches between solid and glass dials. Change them BEFORE tapping method A, B or C.
