H7 ClusterProbe - Shizuku integration

This patch adds a SHIZUKU ACCESS screen to the existing ClusterProbe app.
It checks whether Shizuku is running on the head unit and requests app authorisation.
It does NOT grant GWM SET_DATA or send cluster commands. Shizuku started via ADB
runs with shell-level access, not the factory SceneMode app's system identity.

From your GitHub Codespace repository root:
  unzip -o h7_shizuku_patch.zip
  bash h7_shizuku_patch/install.sh .
  git add App/
  git commit -m "Add Shizuku access to ClusterProbe"
  git pull --rebase origin main
  git push origin main

GitHub Actions will build the APK. Open ClusterProbe > SHIZUKU ACCESS.
Shizuku must already be installed and running on the H7.
