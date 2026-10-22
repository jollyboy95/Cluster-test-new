#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:-.}"
export ROOT
PATCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PATCH_DIR
python3 - <<'PY'
import os,pathlib
r=pathlib.Path(os.environ['ROOT']).resolve()
main=r/'App/Src/Main/Java/Com/clusterprobe/MainActivity.java'
manifest=r/'App/Src/Main/AndroidManifest.xml'
gradle=r/'App/build.gradle'
for f in (main,manifest,gradle):
    if not f.is_file(): raise SystemExit(f'Missing expected file: {f}')
s=main.read_text()
line='        root.addView(button("SHIZUKU ACCESS", v -> startActivity(new Intent(this, ShizukuAccessActivity.class))));\n'
if '"SHIZUKU ACCESS"' not in s:
    anchor='        root.addView(button("FACTORY ADAPTER READ-ONLY CHECK"'
    if anchor not in s: raise SystemExit('MainActivity insertion point not found. No files changed.')
    s=s.replace(anchor,line+anchor,1)
    main.write_text(s)
s=manifest.read_text()
if 'moe.shizuku.manager.permission.API_V23' not in s:
    s=s.replace('    <application', '    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />\n\n    <application',1)
if 'ShizukuAccessActivity' not in s:
    s=s.replace('    </application>', '''        <activity android:name="com.clusterprobe.ShizukuAccessActivity" android:exported="false" />
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
    </application>''',1)
manifest.write_text(s)
s=gradle.read_text()
if 'dev.rikka.shizuku:api:' not in s:
    s += "\n// Shizuku binder API and provider\ndependencies {\n    implementation 'dev.rikka.shizuku:api:13.1.5'\n    implementation 'dev.rikka.shizuku:provider:13.1.5'\n}\n"
    gradle.write_text(s)
source=pathlib.Path(__file__).parent if '__file__' in globals() else None
# install.sh lives alongside Java file, regardless of current working directory
import shutil
shutil.copyfile(pathlib.Path(os.environ['PATCH_DIR'])/'ShizukuAccessActivity.java',r/'App/Src/Main/Java/Com/clusterprobe/ShizukuAccessActivity.java')
print('Installed Shizuku integration. No GWM writes or privilege bypasses added.')
PY
