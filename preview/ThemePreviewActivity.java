package com.clusterprobe;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Read-only, simulated cluster theme previews; no vehicle writes. */
public class ThemePreviewActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setWebViewClient(new WebViewClient());
        web.getSettings().setJavaScriptEnabled(true);
        setContentView(web);
        web.loadUrl("file:///android_asset/h7_theme_preview.html");
    }
    @Override public void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
