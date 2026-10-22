#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"
MAIN="App/Src/Main/Java/Com/clusterprobe/MainActivity.java"
MANIFEST="App/Src/Main/AndroidManifest.xml"
test -f "$MAIN" || { echo "Run from Cluster-test-new repository root"; exit 1; }
test -f "$MANIFEST"
mkdir -p App/Src/Main/Assets
cp preview/index.html App/Src/Main/Assets/h7_theme_preview.html
cp preview/ThemePreviewActivity.java App/Src/Main/Java/Com/clusterprobe/ThemePreviewActivity.java
python3 - <<'PY'
from pathlib import Path
p=Path('App/Src/Main/Java/Com/clusterprobe/MainActivity.java')
s=p.read_text()
needle='        root.addView(button("1. List displays", v -> listDisplays()));'
insert='        root.addView(button("H7 THEME PREVIEW (6 CONCEPTS)", v -> startActivity(new Intent(this, ThemePreviewActivity.class))));\n'
if 'H7 THEME PREVIEW (6 CONCEPTS)' not in s:
    if needle not in s: raise SystemExit('Could not find insertion point in MainActivity.java')
    s=s.replace(needle,insert+needle,1)
    p.write_text(s)
p=Path('App/Src/Main/AndroidManifest.xml')
s=p.read_text()
needle='        <activity\n            android:name="com.clusterprobe.ClusterActivity"'
insert='        <activity android:name="com.clusterprobe.ThemePreviewActivity" android:exported="false" />\n'
if 'com.clusterprobe.ThemePreviewActivity' not in s:
    if needle not in s: raise SystemExit('Could not find insertion point in AndroidManifest.xml')
    s=s.replace(needle,insert+'\n'+needle,1)
    p.write_text(s)
PY
echo 'Preview installed. Commit and push changes to trigger existing Android CI workflow.'
