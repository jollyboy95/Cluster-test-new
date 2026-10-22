package com.clusterprobe;

import com.clusterprobe.IGwmLogcatCallback;

/**
 * Runs with shell UID (bound via Shizuku.bindUserService), in its own process, so it can read
 * logcat tags that belong to other apps/system processes - a normal app-UID process cannot.
 * Purely read-only: starts a `logcat` child process and streams its lines back to the app
 * process via the callback. Never writes to any device, property or vehicle setting.
 */
interface IGwmLogcatService {
    void start(in IGwmLogcatCallback callback);
    void stop();
    void destroy();
}
