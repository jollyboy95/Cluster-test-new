package com.clusterprobe;

/** Delivers raw logcat lines from the shell-UID UserService process back to the app process. */
interface IGwmLogcatCallback {
    void onLine(String line);
    void onStopped(String reason);
}
