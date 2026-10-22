package com.clusterprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Parked-only overlay prototype. No simulated or unverified driving data. */
public class MapFirstView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    public MapFirstView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
    @Override protected void onDraw(Canvas c) {
        float w=getWidth(), h=getHeight(), s=Math.min(w/1920f,h/720f);
        c.save(); c.scale(w/1920f,h/720f);
        // Only the left section is covered; centre/right stay fully transparent for live navigation.
        p.setStyle(Paint.Style.FILL); p.setColor(0xF408101A);
        c.drawRoundRect(new RectF(20,70,480,655),42,42,p);
        p.setColor(0xFFB7C6D4); p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(26); c.drawText("CLUSTER CONCEPT",250,155,p);
        p.setColor(0xFFFFFFFF); p.setTextSize(112); c.drawText("--",250,355,p);
        p.setTextSize(28); c.drawText("km/h · NOT CONNECTED",250,402,p);
        p.setColor(0xFFFFC86B); p.setTextSize(24); c.drawText("PARKED TEST ONLY",250,535,p);
        // Banner warns that this is not an operational instrument cluster.
        p.setColor(0xC908101A); c.drawRoundRect(new RectF(720,14,1280,68),15,15,p);
        p.setColor(0xFFFFC86B); p.setTextSize(25); c.drawText("PROTOTYPE · NOT FOR DRIVING",1000,49,p);
        c.restore();
    }
}
