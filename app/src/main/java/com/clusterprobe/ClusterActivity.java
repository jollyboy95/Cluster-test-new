package com.clusterprobe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/**
 * Hosts a single fullscreen DialView. Launched targeting display 2 (the physical cluster)
 * either via ActivityOptions.setLaunchDisplayId or "am start --display 2". Reads the theme to
 * show and whether to use the experimental capture overlay from its Intent extras, falling back
 * to whatever DialView.theme / useCaptureOverlay are already set to.
 */
public class ClusterActivity extends Activity {

    public static final String EXTRA_THEME_ORDINAL = "theme_ordinal";
    public static final String EXTRA_USE_CAPTURE = "use_capture";

    public static ClusterActivity current;

    private DialView dialView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;

        if (getIntent() != null) {
            int ord = getIntent().getIntExtra(EXTRA_THEME_ORDINAL, -1);
            if (ord >= 0 && ord < DialView.Theme.values().length) {
                DialView.theme = DialView.Theme.values()[ord];
            }
            DialView.useCaptureOverlay = getIntent().getBooleanExtra(EXTRA_USE_CAPTURE, DialView.useCaptureOverlay);
        }

        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
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
        if (current == this) current = null;
        super.onDestroy();
    }
}
