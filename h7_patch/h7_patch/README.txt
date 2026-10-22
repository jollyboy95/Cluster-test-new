H7 Factory Adapter Read-Only Patch

1. Upload this ZIP to your GitHub Codespace and extract it.
2. From the repo root, run: bash /path/to/extracted/install.sh .
   For example, if extracted into h7_patch: bash h7_patch/install.sh .
3. git add App/Src/Main/AndroidManifest.xml App/Src/Main/Java/Com/clusterprobe/MainActivity.java
4. git commit -m "Add read-only GWM adapter diagnostic"
5. git push origin main
6. Build using GitHub Actions and install APK on H7 while parked.
7. Open ClusterProbe, tap FACTORY ADAPTER READ-ONLY CHECK.
8. In phone Termux with ADB connected, run:
   adb logcat -d -s H7AdapterRead:I '*:S' > /sdcard/Download/h7_adapter_read_result.txt
9. Upload h7_adapter_read_result.txt.

This patch does NOT change cluster theme, write vehicle settings, or invoke Binder transactions. Android hidden-API restrictions may prevent service lookup; the diagnostic reports that explicitly.
