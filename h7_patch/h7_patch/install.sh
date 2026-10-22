#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
ROOT="${1:-.}"
python3 - "$ROOT" <<'PY'
import pathlib,sys
root=pathlib.Path(sys.argv[1]).resolve()
manifest=root/'App/Src/Main/AndroidManifest.xml'
main=root/'App/Src/Main/Java/Com/clusterprobe/MainActivity.java'
if not manifest.exists() or not main.exists():
    raise SystemExit('Run bash install.sh /workspaces/Cluster-test-new (or from repository root with bash /path/install.sh .)')
s=manifest.read_text()
permission='    <uses-permission android:name="com.gwm.android.adapter.permission.GET_DATA" />'
if permission not in s:
    s=s.replace('    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />','    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />\n'+permission)
    manifest.write_text(s)
s=main.read_text()
needle='        root.addView(button("1. List displays", v -> listDisplays()));'
addition='        root.addView(button("FACTORY ADAPTER READ-ONLY CHECK", v -> checkFactoryAdapter()));\n'
if 'FACTORY ADAPTER READ-ONLY CHECK' not in s:
    if needle not in s: raise SystemExit('Could not find button insertion point; source changed')
    s=s.replace(needle,addition+needle)
method='''
    /** Diagnostic only: no Binder transactions, vehicle commands, or settings writes. */
    private void checkFactoryAdapter() {
        StringBuilder result = new StringBuilder();
        result.append("=== H7 ADAPTER READ-ONLY CHECK ===\\n");
        result.append("App UID: ").append(android.os.Process.myUid()).append('\\n');
        String permission = "com.gwm.android.adapter.permission.GET_DATA";
        int granted = checkSelfPermission(permission);
        result.append("GET_DATA permission: ")
              .append(granted == android.content.pm.PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED")
              .append('\\n');
        result.append("SET_DATA permission (not requested): ")
              .append(checkSelfPermission("com.gwm.android.adapter.permission.SET_DATA")
                      == android.content.pm.PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED")
              .append('\\n');
        try {
            result.append("Settings.System ipk_setting_theme_config: ")
                  .append(Settings.System.getString(getContentResolver(), "ipk_setting_theme_config"))
                  .append('\\n');
        } catch (Throwable t) { result.append("Settings read failed: ").append(t).append('\\n'); }
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "gwm_adapter");
            if (binder == null) result.append("gwm_adapter: null binder\\n");
            else {
                result.append("gwm_adapter binder alive: ").append(binder.isBinderAlive()).append('\\n');
                result.append("gwm_adapter descriptor: ").append(binder.getInterfaceDescriptor()).append('\\n');
            }
        } catch (Throwable t) {
            result.append("gwm_adapter lookup failed (possibly hidden API restrictions): ")
                  .append(t).append('\\n');
        }
        result.append("NO binder transactions or cluster writes performed.\\n");
        String output = result.toString();
        log(output);
        for (String line : output.split("\\n")) Log.i("H7AdapterRead", line);
    }
'''
if 'private void checkFactoryAdapter()' not in s:
    mark='    private Button button(String text, View.OnClickListener l) {'
    if mark not in s: raise SystemExit('Could not find method insertion point; source changed')
    s=s.replace(mark,method+'\n'+mark)
    main.write_text(s)
print('Installed read-only adapter check into',root)
PY
