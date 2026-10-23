package com.clusterprobe.aareceiver;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Hosts our own Wi-Fi access point using the public, unprivileged
 * WifiManager.startLocalOnlyHotspot() API (added API 26 - available on this head unit's API 28),
 * so the phone has somewhere to join before opening the real Android Auto TCP session.
 *
 * This is the Android-API-correct equivalent of what aa-proxy-rs does with hostapd+dnsmasq on
 * Linux (see src/bluetooth.rs's hostapd_hw_mode_for_channel/create-AP code, read during the
 * wireless-bootstrap research this is built from) - Android doesn't let an app shell out to
 * hostapd, but LocalOnlyHotspot gives an app-owned WPA2 AP through a real, documented, non-root
 * API instead, which is the right tool here rather than a vendor-specific workaround.
 *
 * Requires ACCESS_FINE_LOCATION (declared+granted at runtime) on API 28 - LocalOnlyHotspot's
 * credentials are treated as location-adjacent data by the platform, same as Wi-Fi scan results.
 */
public final class AaWirelessHotspot {

    public interface Listener {
        void onStarted(String ssid, String passphrase, String bssid);
        void onFailed(String reason);
        void onStopped();
    }

    private WifiManager.LocalOnlyHotspotReservation reservation;
    private boolean running = false;

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void start(Context context, Listener listener) {
        if (running) {
            listener.onFailed("already running");
            return;
        }
        WifiManager wifiManager =
                (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            listener.onFailed("WifiManager unavailable");
            return;
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                synchronized (AaWirelessHotspot.this) {
                    reservation = res;
                    running = true;
                }
                WifiConfiguration config = res.getWifiConfiguration();
                if (config == null) {
                    listener.onFailed("hotspot started but WifiConfiguration was null "
                            + "(SoftApConfiguration-only device? needs API 30+ handling)");
                    return;
                }
                String ssid = stripQuotes(config.SSID);
                String passphrase = config.preSharedKey;
                // WifiConfiguration has no reliable BSSID field for a LocalOnlyHotspot AP on
                // every API level - leave it blank rather than guess; AaWirelessInfoResponse's
                // bssid field is required by the .proto but real phones have been observed (per
                // aa-proxy-rs's own external_ap comments) to tolerate an empty one when ssid/key
                // are correct, since they primarily join by SSID+passphrase.
                String bssid = "";
                listener.onStarted(ssid, passphrase, bssid);
            }

            @Override
            public void onStopped() {
                synchronized (AaWirelessHotspot.this) {
                    running = false;
                    reservation = null;
                }
                listener.onStopped();
            }

            @Override
            public void onFailed(int reason) {
                synchronized (AaWirelessHotspot.this) {
                    running = false;
                    reservation = null;
                }
                listener.onFailed("LocalOnlyHotspotCallback.onFailed reason=" + reason);
            }
        }, mainHandler);
    }

    public synchronized void stop() {
        if (reservation != null) {
            reservation.close();
            reservation = null;
        }
        running = false;
    }

    private static String stripQuotes(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
