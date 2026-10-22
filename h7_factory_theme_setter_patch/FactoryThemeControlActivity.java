package com.clusterprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Factory theme control UI. No speculative Binder transactions or Settings writes. */
public final class FactoryThemeControlActivity extends Activity {
    private static final String WRITE_PERMISSION = "com.gwm.android.adapter.permission.SET_DATA";
    private TextView status;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(28, 24, 28, 24);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        TextView heading = new TextView(this);
        heading.setText("H7 FACTORY THEME CONTROL");
        heading.setTextSize(23);
        root.addView(heading);
        TextView explanation = new TextView(this);
        explanation.setText("Factory IDs only. This screen does not use concept themes or alter Settings.System. The actual GWM setter is not callable from this unsigned app without a verified privileged interface.");
        explanation.setPadding(0, 16, 0, 16);
        root.addView(explanation);
        status = new TextView(this);
        status.setTextSize(16);
        root.addView(status);
        addTheme(root, "Guide (stock)", 4);
        addTheme(root, "Super Sport", 7);
        addTheme(root, "Mecha", 10);
        addTheme(root, "Super Sport Plus", 11);
        Button refresh = new Button(this);
        refresh.setText("REFRESH STATUS");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh);
        setContentView(scroll);
        refreshStatus();
    }

    private void addTheme(LinearLayout root, String label, int id) {
        Button button = new Button(this);
        button.setText(label + "  |  ID " + id);
        button.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Factory theme " + id)
            .setMessage("This requests a real factory cluster change, not a visual preview. Vehicle theme support is unverified. Continue to the permission check?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", (dialog, which) -> requestTheme(id))
            .show());
        root.addView(button);
    }

    private void refreshStatus() {
        boolean allowed = checkSelfPermission(WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        String saved;
        try { saved = Settings.System.getString(getContentResolver(), "ipk_setting_theme_config"); }
        catch (Throwable t) { saved = "unavailable: " + t.getClass().getSimpleName(); }
        status.setText("GWM SET_DATA permission: " + (allowed ? "GRANTED" : "DENIED")
                + "\nSaved Android theme: " + saved
                + "\nLive factory setter: NOT CONFIGURED\n");
    }

    private void requestTheme(int themeId) {
        if (themeId != 4 && themeId != 7 && themeId != 10 && themeId != 11) {
            showResult("Invalid theme ID. No command sent.");
            return;
        }
        if (checkSelfPermission(WRITE_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            showResult("Factory SET_DATA permission denied. No command sent; cluster unchanged.");
            return;
        }
        // Do not fabricate an IGwmAdapterService transaction or bypass GWM permissions.
        // The platform-specific writer must be integrated only after its exact AIDL signature
        // and controller response contract have been established.
        showResult("Permission granted, but verified factory setter binding is unavailable. No command sent.");
    }

    private void showResult(String message) {
        status.setText(message);
        new AlertDialog.Builder(this).setTitle("Factory setter status")
            .setMessage(message).setPositiveButton("OK", null).show();
    }
}
