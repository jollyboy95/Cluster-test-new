package com.clusterprobe;

import android.app.Activity;
import android.os.Bundle;

public class ClusterActivity extends Activity {
    public static ClusterActivity instance;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        setContentView(new DialView(this));
    }

    @Override protected void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
