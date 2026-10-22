#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:-.}"
ROOT="$(cd "$ROOT" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN="$ROOT/App/Src/Main/Java/Com/clusterprobe/MainActivity.java"
MANIFEST="$ROOT/App/Src/Main/AndroidManifest.xml"
DEST="$ROOT/App/Src/Main/Java/Com/clusterprobe/FactoryThemeControlActivity.java"
[[ -f "$MAIN" && -f "$MANIFEST" ]] || { echo "Repository App files not found: $ROOT"; exit 1; }
cp "$HERE/FactoryThemeControlActivity.java" "$DEST"
python3 - "$MAIN" "$MANIFEST" <<'PY'
import sys
from pathlib import Path
main, manifest = map(Path,sys.argv[1:])
s=main.read_text()
line='        root.addView(button("FACTORY THEME CONTROL", v -> startActivity(new Intent(this, FactoryThemeControlActivity.class))));'
if line not in s:
    anchor='        root.addView(button("FACTORY ADAPTER READ-ONLY CHECK", v -> checkFactoryAdapter()));'
    if anchor not in s: raise SystemExit('Could not find expected MainActivity anchor; no modifications made')
    s=s.replace(anchor, line+'\n'+anchor)
    main.write_text(s)
s=manifest.read_text()
perm='    <uses-permission android:name="com.gwm.android.adapter.permission.SET_DATA" />'
if perm not in s:
    s=s.replace('    <uses-permission android:name="com.gwm.android.adapter.permission.GET_DATA" />','    <uses-permission android:name="com.gwm.android.adapter.permission.GET_DATA" />\n'+perm)
activity='        <activity android:name="com.clusterprobe.FactoryThemeControlActivity" android:exported="false" />'
if activity not in s:
    s=s.replace('        <activity android:name="com.clusterprobe.ThemePreviewActivity" android:exported="false" />', activity+'\n        <activity android:name="com.clusterprobe.ThemePreviewActivity" android:exported="false" />')
manifest.write_text(s)
PY
echo "Installed Factory Theme Control UI into $ROOT"
echo "NOTE: SET_DATA is signature|privileged. Live theme writes are intentionally not enabled."
