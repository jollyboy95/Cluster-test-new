From the root of your GitHub Codespace repository:
  unzip -o h7_clusterprobe2_installer.zip -d /tmp/h7-installer
  bash /tmp/h7-installer/install.sh .
  git add ClusterProbe-2 .github/workflows/clusterprobe-2.yml
  git commit -m "Add ClusterProbe 2 project and APK build"
  git push
Then GitHub > Actions > Build ClusterProbe 2 APK > Run workflow (or push to main).
Download artifact ClusterProbe-2-debug-apk.
IMPORTANT: This installs the uploaded v0.3 project as a separate folder, so it DOES NOT replace your existing Shizuku-enabled ClusterProbe. The uploaded app has the same applicationId com.clusterprobe, so installing its APK on your head unit would replace the installed app (and may require uninstall if signatures differ).
