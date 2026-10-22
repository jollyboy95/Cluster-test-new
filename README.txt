H7 Theme Preview integration for jollyboy95/Cluster-test-new

GitHub connection is read-only for writes (GitHub API returned 403), so apply using Codespaces.

1. In your repository, open Code > Codespaces > your codespace.
2. Upload the ZIP into the repository root in the Codespaces file explorer.
3. In the Codespaces terminal:
   unzip -o H7_ClusterRepo_ThemePreview_Patch.zip -d .
   bash install.sh
   git add App/Src/Main/Assets/h7_theme_preview.html App/Src/Main/Java/Com/clusterprobe/ThemePreviewActivity.java App/Src/Main/Java/Com/clusterprobe/MainActivity.java App/Src/Main/AndroidManifest.xml
   git commit -m "Add read-only six-theme preview"
   git push
4. Open repository Actions > Android CI > newest successful run > ClusterProbe-debug-apk.

No new GitHub workflow is needed; the repository already has .github/workflows/android.yml.
The preview is a conceptual design, not extracted factory artwork. It does not write cluster values.
