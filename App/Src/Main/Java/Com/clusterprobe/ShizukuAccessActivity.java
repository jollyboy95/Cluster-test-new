package com.clusterprobe;

import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import rikka.shizuku.Shizuku;

/** Shizuku connection/authorisation only. Never sends vehicle commands. */
public class ShizukuAccessActivity extends Activity {
    private static final int REQUEST_CODE = 4201;
    private TextView status;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_CODE) runOnUiThread(this::refresh);
    };
    private final Shizuku.OnBinderReceivedListener receivedListener = () -> runOnUiThread(this::refresh);
    private final Shizuku.OnBinderDeadListener deadListener = () -> runOnUiThread(this::refresh);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(28, 28, 28, 28);
        TextView heading = new TextView(this);
        heading.setText("H7 SHIZUKU ACCESS");
        heading.setTextSize(23);
        layout.addView(heading);
        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, 20, 0, 20);
        layout.addView(status);
        Button request = new Button(this);
        request.setText("AUTHORISE CLUSTERPROBE IN SHIZUKU");
        request.setOnClickListener(v -> {
            try {
                if (!Shizuku.pingBinder()) {
                    status.setText("Shizuku service is not reachable. Start Shizuku on the H7 first.");
                    return;
                }
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    refresh();
                } else {
                    Shizuku.requestPermission(REQUEST_CODE);
                }
            } catch (Throwable t) { status.setText("Shizuku authorisation failed: " + t); }
        });
        layout.addView(request);
        Button refresh = new Button(this);
        refresh.setText("REFRESH SHIZUKU STATUS");
        refresh.setOnClickListener(v -> refresh());
        layout.addView(refresh);
        setContentView(layout);
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener);
            Shizuku.addBinderReceivedListenerSticky(receivedListener);
            Shizuku.addBinderDeadListener(deadListener);
        } catch (Throwable t) { status.setText("Shizuku library unavailable: " + t); }
        refresh();
    }
    private void refresh() {
        try {
            boolean running = Shizuku.pingBinder();
            boolean authorised = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            String message = "Shizuku service: " + (running ? "CONNECTED" : "NOT CONNECTED")
                    + "\nClusterProbe authorisation: " + (authorised ? "GRANTED" : "NOT GRANTED")
                    + "\n\nShizuku started via ADB uses shell UID 2000. It does not grant the GWM "
                    + "signature/privileged SET_DATA permission. No cluster commands are sent from this screen.";
            status.setText(message);
        } catch (Throwable t) { status.setText("Cannot query Shizuku: " + t); }
    }
    @Override protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener);
            Shizuku.removeBinderReceivedListener(receivedListener);
            Shizuku.removeBinderDeadListener(deadListener);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
