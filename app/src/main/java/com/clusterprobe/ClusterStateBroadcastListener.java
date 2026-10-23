package com.clusterprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.Set;

/**
 * Listens for "com.projection.action.CLUSTER_STATE" - a broadcast action found declared as a
 * <protected-broadcast> in com.gwm.app.aap's AndroidManifest.xml (the factory Android Auto host
 * app, reverse-read read-only from the real AndroidAuto.apk/.odex/.vdex pulled off this car - see
 * docs/H7_DISPLAY_ARCHITECTURE.md). A <protected-broadcast> declaration only restricts who may
 * SEND that action system-wide (non-system senders are rejected) - it places no restriction at
 * all on which apps may REGISTER to RECEIVE it. So, unlike every other avenue probed so far in
 * this project, listening for this one requires no special permission and no signature-level
 * trust: any normal app can do exactly what this class does.
 *
 * This is a genuine unknown, not a confirmed result: we don't yet know whether com.gwm.app.aap
 * actually sends this broadcast system-wide (vs. only to itself via an explicit/internal
 * mechanism), what triggers it, or what extras (if any) it carries. This class exists purely to
 * find out, by listening and logging whatever arrives, exactly as-is - read-only, no reply sent,
 * nothing re-broadcast, nothing written anywhere.
 */
public class ClusterStateBroadcastListener {

    public interface StatusListener { void onStatus(String msg); }

    public static final String ACTION_CLUSTER_STATE = "com.projection.action.CLUSTER_STATE";

    private static ClusterStateBroadcastListener instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context appContext;
    private BroadcastReceiver receiver;
    private int receivedCount = 0;

    public static void start(Context context, StatusListener status) {
        stop();
        instance = new ClusterStateBroadcastListener();
        instance.doStart(context.getApplicationContext(), status);
    }

    public static void stop() {
        if (instance == null) return;
        instance.doStop();
        instance = null;
    }

    private void log(StatusListener status, String msg) {
        if (status != null) main.post(() -> status.onStatus(msg));
    }

    private void doStart(Context context, StatusListener status) {
        appContext = context;
        log(status, "Cluster-state broadcast listener: registering for " + ACTION_CLUSTER_STATE
                + " (a <protected-broadcast> declared by com.gwm.app.aap - receiving it needs no "
                + "special permission, unlike every other avenue probed so far). Listening in the "
                + "background; anything received will be logged here, including after this probe's "
                + "other checks finish. Press \"Stop cluster-renderer probe\" to stop listening.");
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                receivedCount++;
                log(status, "Cluster-state broadcast #" + receivedCount + " received: " + describe(intent));
            }
        };
        try {
            context.registerReceiver(receiver, new IntentFilter(ACTION_CLUSTER_STATE));
        } catch (Throwable t) {
            log(status, "Cluster-state broadcast listener: registerReceiver failed: " + t);
            receiver = null;
        }
    }

    private String describe(Intent intent) {
        StringBuilder sb = new StringBuilder();
        sb.append("action=").append(intent.getAction());
        Bundle extras = intent.getExtras();
        if (extras == null || extras.isEmpty()) {
            sb.append(" (no extras)");
            return sb.toString();
        }
        Set<String> keys = extras.keySet();
        sb.append(" extras={");
        boolean first = true;
        for (String key : keys) {
            if (!first) sb.append(", ");
            first = false;
            Object value;
            try {
                value = extras.get(key);
            } catch (Throwable t) {
                value = "<unreadable: " + t + ">";
            }
            sb.append(key).append('=').append(value);
        }
        sb.append('}');
        return sb.toString();
    }

    private void doStop() {
        if (receiver != null && appContext != null) {
            try {
                appContext.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
        }
        receiver = null;
        appContext = null;
    }
}
