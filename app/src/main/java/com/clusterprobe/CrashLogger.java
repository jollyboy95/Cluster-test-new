package com.clusterprobe;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Installs a global uncaught-exception handler that writes the full stack trace to a file under
 * this app's own external-files directory (no storage permission needed, readable via adb without
 * root): /sdcard/Android/data/com.clusterprobe/files/crash.log
 *
 * Pull it after a crash with:
 *   adb pull /sdcard/Android/data/com.clusterprobe/files/crash.log
 */
public class CrashLogger {

    public static void install(Context ctx) {
        Context app = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                writeCrash(app, ex);
            } catch (Throwable ignored) {
                // never let the crash logger itself cause a second crash
            }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }

    private static void writeCrash(Context app, Throwable ex) {
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        File f = new File(dir, "crash.log");
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        try (FileWriter w = new FileWriter(f, true)) {
            w.write("\n==== " + ts + " ==== device=" + Build.MANUFACTURER + " " + Build.MODEL
                    + " sdk=" + Build.VERSION.SDK_INT + " ====\n");
            w.write(sw.toString());
            w.write("\n");
        } catch (Exception ignored) {
        }
    }
}
