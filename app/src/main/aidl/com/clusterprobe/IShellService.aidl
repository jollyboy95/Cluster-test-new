package com.clusterprobe;

/**
 * Runs with shell UID (bound via Shizuku.bindUserService), in its own process - same pattern as
 * IGwmLogcatService, generalized to run an arbitrary one-shot shell command instead of streaming
 * logcat. Exists for ThirdPartyAaLauncher's "am start --display <id>" calls: Shizuku's own public
 * API does not expose a usable newProcess() for an app to call directly (it is a private method
 * on the version of the library this project builds against - a real build-time discovery, not
 * assumed), so this runs the command from inside a UserService process that already has shell
 * UID, using a plain ProcessBuilder the same way GwmLogcatUserService already does.
 */
interface IShellService {
    /** Blocks until the command exits (fine for a quick "am start") and returns a short
     *  human-readable summary ("exit=0 stdout=... stderr=..."), not the raw streams. */
    String runOnce(String cmd);
    void destroy();
}
