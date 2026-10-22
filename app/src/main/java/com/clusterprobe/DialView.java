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
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws one of several selectable cluster themes.
 *
 * ROUND_PAIR themes (Crema): two round dial bitmaps (speed left, RPM right), no background mask,
 * so everything outside the discs stays fully transparent and whatever is behind the overlay
 * (stock gauges, map) shows through there.
 *
 * MAP_WINDOW themes (Apex): a black bitmap mask with a soft see-through cut-out over the measured
 * stock-map rectangle, PLUS live-drawn speed/RPM edge arcs on top, like the Apex HTML concept but
 * as plain Canvas drawing (cheap - no WebView), so the map shows through the hole and the stock
 * gauges stay hidden under the black everywhere else.
 */
public class DialView extends View {

    public enum Theme {
        CREMA_GLASS("Crema (glass)", Kind.ROUND_PAIR, "crema_speed_dial_glass_600.png", "crema_rpm_dial_glass_600.png", null),
        CREMA_SOLID("Crema (solid)", Kind.ROUND_PAIR, "crema_speed_dial_solid_600.png", "crema_rpm_dial_solid_600.png", null),
        APEX_WIDE("Apex (wide map)", Kind.MAP_WINDOW, null, null, "apex_mask_wide_1920x720.png"),
        APEX_TIGHT("Apex (tight map)", Kind.MAP_WINDOW, null, null, "apex_mask_tight_1920x720.png"),
        APEX_MAX("Apex (max map)", Kind.MAP_WINDOW, null, null, "apex_mask_max_1920x720.png"),
        HUD_CLEAN("HUD (clean)", Kind.HUD, null, null, null);

        enum Kind { ROUND_PAIR, MAP_WINDOW, HUD }
        final String label; final Kind kind; final String speedAsset, rpmAsset, maskAsset;
        Theme(String label, Kind kind, String speedAsset, String rpmAsset, String maskAsset) {
            this.label = label; this.kind = kind; this.speedAsset = speedAsset; this.rpmAsset = rpmAsset; this.maskAsset = maskAsset;
        }
    }

    /** Set before the view is attached / before a method launches the view on a display. */
    public static Theme theme = Theme.APEX_WIDE;

    /** Experimental: when set (and non-null), the MAP_WINDOW themes paint this bitmap into the
     *  cut-out area instead of leaving it transparent. Fed by CaptureHelper's latest captured
     *  frame - see MainActivity's "Test map capture" button. Read on the UI thread only. */
    public static volatile Bitmap captureOverlay;
    public static volatile boolean useCaptureOverlay = false;

    /** When off (the default), the Apex themes skip their big centre speed readout, since the
     *  real stock digital readout is now confirmed visible through the cut-out anyway and a
     *  second, fake/demo number just duplicates (and contradicts) it. Flip on if you want it
     *  back - e.g. for the round-pair Crema themes it's not drawn here at all regardless. */
    public static volatile boolean showCenterReadout = false;

    /** Set true once real vehicle data starts arriving (VehicleDataSource) - stops every
     *  DialView's own fake demo animation so drawn values don't fight with real ones. */
    static volatile boolean liveMode = false;
    private static volatile float liveSpeedKmh = 0f;
    private static volatile float liveRpm = 0f;
    private static final Set<DialView> activeViews = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void pushLiveSpeed(float kmh) { liveMode = true; liveSpeedKmh = kmh; broadcastLive(); }
    public static void pushLiveRpm(float rpm) { liveMode = true; liveRpm = rpm; broadcastLive(); }

    private static void broadcastLive() {
        for (DialView v : activeViews) v.post(() -> v.setValues(liveSpeedKmh, liveRpm));
    }

    /** Force every currently-open DialView (cluster + HUD, Activity + overlay windows) to redraw
     *  right away - for toggles like showCenterReadout/useCaptureOverlay that don't change
     *  speed/rpm/theme, so they wouldn't otherwise be picked up until the next frame. */
    public static void invalidateAll() {
        for (DialView v : activeViews) v.post(v::invalidate);
    }

    private static final float MAX_SPEED = 220f;
    private static final float MAX_RPM = 7000f;
    private static final float REDLINE_RPM = 5800f;

    private final Context ctx;
    private Bitmap speedBmp, rpmBmp, maskBmp;
    private Theme loadedTheme;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private float speed = 0f, rpm = 0f;

    public DialView(Context c) {
        super(c);
        this.ctx = c;
        needle.setColor(0xFFFFF8EE); needle.setStrokeWidth(7f); needle.setStrokeCap(Paint.Cap.ROUND);
        hub.setColor(0xFFE3C29B);
        textPaint.setColor(0xFFF4F6F8); textPaint.setTextAlign(Paint.Align.CENTER);
        arcPaint.setStyle(Paint.Style.STROKE); arcPaint.setStrokeCap(Paint.Cap.ROUND);
        loadAssetsForTheme(theme);
    }

    private static Bitmap load(Context c, String name) {
        if (name == null) return null;
        try (InputStream in = c.getAssets().open(name)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private void loadAssetsForTheme(Theme t) {
        recycleAll();
        speedBmp = load(ctx, t.speedAsset);
        rpmBmp = load(ctx, t.rpmAsset);
        maskBmp = load(ctx, t.maskAsset);
        loadedTheme = t;
    }

    private void recycleAll() {
        if (speedBmp != null) speedBmp.recycle();
        if (rpmBmp != null) rpmBmp.recycle();
        if (maskBmp != null) maskBmp.recycle();
        speedBmp = rpmBmp = maskBmp = null;
    }

    /** Call after changing DialView.theme while this view is already showing, to swap the bitmaps live. */
    public void refreshTheme() {
        if (loadedTheme != theme) {
            loadAssetsForTheme(theme);
            invalidate();
        }
    }

    /** Call this later with real vehicle data instead of the demo animation. */
    public void setValues(float speedKmh, float rpmValue) {
        speed = speedKmh; rpm = rpmValue; invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        activeViews.add(this);
        if (liveMode) {
            setValues(liveSpeedKmh, liveRpm); // show whatever we already have straight away
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(7000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.REVERSE);
        anim.addUpdateListener(a -> {
            if (liveMode) { anim.cancel(); return; } // real data started after we began faking it
            float t = (float) a.getAnimatedValue();
            speed = t * 200f; rpm = t * 6500f;
            invalidate();
        });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        activeViews.remove(this);
        if (anim != null) anim.cancel();
        recycleAll();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        refreshTheme(); // cheap no-op unless the theme actually changed since the last frame
        int w = getWidth(), h = getHeight();
        if (loadedTheme.kind == Theme.Kind.ROUND_PAIR) {
            drawRoundPair(canvas, w, h);
        } else if (loadedTheme.kind == Theme.Kind.HUD) {
            drawHudClean(canvas, w, h);
        } else {
            drawMapWindow(canvas, w, h);
        }
        textPaint.setTextSize(Math.max(14, h * 0.035f));
        textPaint.setAlpha(140);
        canvas.drawText(loadedTheme.label + "  " + w + "x" + h, w / 2f, h * 0.085f, textPaint);
        textPaint.setAlpha(255);
    }

    // ---- Crema: two round dial bitmaps with sweeping needles ----
    private void drawRoundPair(Canvas canvas, int w, int h) {
        float side = Math.min(h * 0.84f, w * 0.30f);
        float cy = h * 0.53f;
        drawDial(canvas, speedBmp, w * 0.165f, cy, side, speed / MAX_SPEED);
        drawDial(canvas, rpmBmp, w * 0.835f, cy, side, rpm / MAX_RPM);
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

    // ---- Apex: black map-cutout mask + live-drawn edge arcs, same geometry as the oval cut-out ----
    private void drawMapWindow(Canvas canvas, int w, int h) {
        float cx = w / 2f, cy = h * 0.52f;
        float rx = w * 0.36f, ry = h * 0.31f;

        if (useCaptureOverlay && captureOverlay != null) {
            // Centre-crop the captured frame into the hole's bounding box, then let the mask's
            // transparent hole reveal it (drawn in the normal order below).
            RectF dst = new RectF(cx - rx * 1.05f, cy - ry * 1.05f, cx + rx * 1.05f, cy + ry * 1.05f);
            canvas.drawBitmap(captureOverlay, null, dst, bmpPaint);
        }
        if (maskBmp != null) canvas.drawBitmap(maskBmp, null, new RectF(0, 0, w, h), bmpPaint);
        float sweepDeg = 100f; // degrees of arc used per side, centred on the vertical
        float arcW = Math.max(4, h * 0.012f);

        float rpmT = clamp(rpm / MAX_RPM, 0, 1);
        float speedT = clamp(speed / MAX_SPEED, 0, 1);

        arcPaint.setStrokeWidth(arcW);
        arcPaint.setColor(rpm >= REDLINE_RPM ? 0xFFFF5C46 : 0xFF5AD1FF);
        drawEdgeArc(canvas, cx, cy, rx, ry, true, rpmT, sweepDeg);
        arcPaint.setColor(0xFF5AD1FF);
        drawEdgeArc(canvas, cx, cy, rx, ry, false, speedT, sweepDeg);

        if (showCenterReadout) {
            textPaint.setTextSize(h * 0.15f);
            canvas.drawText(String.valueOf(Math.max(0, Math.round(speed))), cx, h * 0.165f, textPaint);
            textPaint.setTextSize(h * 0.045f);
            textPaint.setAlpha(180);
            canvas.drawText("km/h", cx, h * 0.225f, textPaint);
            textPaint.setAlpha(255);
        }
    }

    private void drawEdgeArc(Canvas canvas, float cx, float cy, float rx, float ry, boolean left, float t, float sweepDeg) {
        RectF oval = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
        float start = left ? (180f - sweepDeg / 2f) : (-sweepDeg / 2f);
        canvas.drawArc(oval, start, sweepDeg * clamp(t, 0, 1), false, arcPaint);
    }

    // ---- HUD: crisp, minimal, antialiased - built to replace the stock blurry HUD arrows with
    // something sharp. No mask/map business here, this is drawn directly for the tiny HUD canvas. ----
    private void drawHudClean(Canvas canvas, int w, int h) {
        float cx = w / 2f, cy = h * 0.52f;
        textPaint.setColor(0xFFEAF6FF);
        textPaint.setTextSize(h * 0.52f);
        canvas.drawText(String.valueOf(Math.max(0, Math.round(speed))), cx, cy, textPaint);
        textPaint.setTextSize(h * 0.15f);
        textPaint.setAlpha(210);
        canvas.drawText("km/h", cx, cy + h * 0.24f, textPaint);
        textPaint.setAlpha(255);
        textPaint.setColor(0xFFF4F6F8);

        // thin accent tick under the number, filling with RPM the way the edge arcs do elsewhere
        float rpmT = clamp(rpm / MAX_RPM, 0, 1);
        float tickW = w * 0.5f;
        float ty = h * 0.86f;
        arcPaint.setStrokeWidth(Math.max(3, h * 0.02f));
        arcPaint.setColor(0x33FFFFFF);
        canvas.drawLine(cx - tickW / 2, ty, cx + tickW / 2, ty, arcPaint);
        arcPaint.setColor(rpm >= REDLINE_RPM ? 0xFFFF5C46 : 0xFF5AD1FF);
        canvas.drawLine(cx - tickW / 2, ty, cx - tickW / 2 + tickW * rpmT, ty, arcPaint);
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
}
