package com.clusterprobe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Runs with shell UID in its own process (Shizuku spawns this via bindUserService), same pattern
 * as GwmLogcatUserService - generalized to run an arbitrary one-shot command via ProcessBuilder
 * instead of tailing logcat. Used by ThirdPartyAaLauncher for "am start --display <id>", because
 * the shell UID can target a non-default display the way an ordinary app-UID process cannot (see
 * MainActivity.launchOnDisplay()'s own in-process SecurityException for the same restriction).
 *
 * Must have a public no-arg constructor - Shizuku instantiates it directly by reflection in the
 * spawned process.
 */
public class ShellUserService extends IShellService.Stub {

    public ShellUserService() {
    }

    @Override
    public String runOnce(String cmd) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(false);
            process = pb.start();
            String out = readAll(process.getInputStream());
            String err = readAll(process.getErrorStream());
            int exit = process.waitFor();
            return "exit=" + exit
                    + (out.isEmpty() ? "" : " stdout=" + out)
                    + (err.isEmpty() ? "" : " stderr=" + err);
        } catch (Exception e) {
            return "exec failed: " + e;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readAll(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append(' ');
            }
        } catch (IOException ignored) {
        }
        return sb.toString().trim();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
