package com.clusterprobe;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hosts a single fullscreen DialView. Launched targeting a chosen display (cluster = 2, HUD = 3)
 * via ActivityOptions.setLaunchDisplayId. Reads the theme to show and whether to use the
 * experimental capture overlay from its Intent extras, falling back to whatever
 * DialView.theme / useCaptureOverlay are already set to.
 *
 * Tracked by displayId in a static map rather than a single "current" reference, because this app
 * can have one instance open on display 2 AND another on display 3 at the same time - a single
 * reference meant launching the second one silently made the first impossible to close from the
 * UI (the only way out was force-stopping the whole app). stopAll()/MainActivity's "Stop cluster
 * window" button now closes every tracked instance, not just the most recently opened one.
 */
public class ClusterActivity extends Activity {

    public static final String EXTRA_THEME_ORDINAL = "theme_ordinal";
    public static final String EXTRA_USE_CAPTURE = "use_capture";

    private static final Set<ClusterActivity> open =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static boolean hasAnyOpen() { return !open.isEmpty(); }

    public static void stopAll() {
        for (ClusterActivity a : open.toArray(new ClusterActivity[0])) {
            try { a.finish(); } catch (Throwable ignored) {}
        }
    }

    public static void applyThemeChangeToAll() {
        for (ClusterActivity a : open) a.applyThemeChange();
    }

    private DialView dialView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open.add(this);

        if (getIntent() != null) {
            int ord = getIntent().getIntExtra(EXTRA_THEME_ORDINAL, -1);
            if (ord >= 0 && ord < DialView.Theme.values().length) {
                DialView.theme = DialView.Theme.values()[ord];
            }
            DialView.useCaptureOverlay = getIntent().getBooleanExtra(EXTRA_USE_CAPTURE, DialView.useCaptureOverlay);
        }

        // IMPORTANT: no opaque background here. The window uses a translucent theme (see the
        // manifest) specifically so the MAP_WINDOW themes' transparent cut-out can actually show
        // whatever's on this display underneath us - an earlier version forced a solid black
        // background here, which silently defeated every mask's transparency regardless of what
        // was really behind it. DialView itself stays transparent except where it explicitly
        // draws (dials, mask, text).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        dialView = new DialView(this);
        setContentView(dialView);
    }

    /** Called by MainActivity after it switches DialView.theme, so an already-open cluster
     *  window updates live instead of needing to be relaunched. */
    public void applyThemeChange() {
        if (dialView != null) dialView.refreshTheme();
    }

    @Override
    protected void onDestroy() {
        open.remove(this);
        super.onDestroy();
    }
}
