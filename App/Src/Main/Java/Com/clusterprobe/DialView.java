package com.clusterprobe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.io.InputStream;

/** Draws the speed and RPM dial PNGs with sweeping demo needles. Nothing else, to keep memory tiny. */
public class DialView extends View {
    private static final float MAX_SPEED = 220f;   // must match the dial image
    private static final float MAX_RPM = 7000f;    // must match the dial image

    /** Toggled from the main screen before launching a method. */
    public static boolean useMask = true;     // black surround with a see-through oval, hides the stock dials
    public static boolean solidDials = true;  // opaque dials, stock dials can't show through

    private Bitmap speedBmp, rpmBmp, maskBmp;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private float speed = 0f, rpm = 0f;

    public DialView(Context c) {
        super(c);
        String kind = solidDials ? "solid" : "glass";
        speedBmp = load(c, "crema_speed_dial_" + kind + "_600.png");
        rpmBmp = load(c, "crema_rpm_dial_" + kind + "_600.png");
        if (useMask) maskBmp = load(c, "apex_map_window_1920x720.png");
        needle.setColor(0xFFFFF8EE); needle.setStrokeWidth(7f); needle.setStrokeCap(Paint.Cap.ROUND);
        hub.setColor(0xFFE3C29B);
        label.setColor(0xCCE3C29B); label.setTextSize(26f); label.setTextAlign(Paint.Align.CENTER);
    }

    private static Bitmap load(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** Call this later with real vehicle data instead of the demo animation. */
    public void setValues(float speedKmh, float rpmValue) {
        speed = speedKmh; rpm = rpmValue; invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(7000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            speed = t * 200f; rpm = t * 6500f; invalidate();
        });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (maskBmp != null) canvas.drawBitmap(maskBmp, null, new RectF(0, 0, w, h), bmpPaint);
        float side = Math.min(h * 0.84f, w * 0.30f);
        float cy = h * 0.53f;
        drawDial(canvas, speedBmp, w * 0.165f, cy, side, speed / MAX_SPEED);
        drawDial(canvas, rpmBmp, w * 0.835f, cy, side, rpm / MAX_RPM);
        canvas.drawText("ClusterProbe  " + w + "x" + h, w / 2f, h * 0.10f, label);
    }

    private void drawDial(Canvas canvas, Bitmap bmp, float cx, float cy, float side, float t) {
        RectF dst = new RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2);
        if (bmp != null) canvas.drawBitmap(bmp, null, dst, bmpPaint);
        else { hub.setStyle(Paint.Style.STROKE); hub.setStrokeWidth(4f); canvas.drawOval(dst, hub); hub.setStyle(Paint.Style.FILL); }
        t = Math.max(0f, Math.min(1f, t));
        double a = Math.toRadians(135 + 270 * t);
        float r = side / 2f * 0.84f;
        canvas.drawLine(cx, cy, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, needle);
        canvas.drawCircle(cx, cy, side * 0.025f, hub);
    }
}
