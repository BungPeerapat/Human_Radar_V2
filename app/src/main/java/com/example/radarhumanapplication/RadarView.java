package com.example.radarhumanapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

public class RadarView extends View {

    private static final float MAX_RANGE = 6000f; // mm
    private static final int TRAIL_MAX = 20;
    private static final int[] TARGET_COLORS_NORMAL = {
            Color.parseColor("#FF4444"),
            Color.parseColor("#44FF44"),
            Color.parseColor("#4488FF")
    };
    // Night-mode (red theme) target tints — preserves dark adaptation.
    private static final int[] TARGET_COLORS_NIGHT = {
            Color.parseColor("#FF6666"),
            Color.parseColor("#FF3333"),
            Color.parseColor("#CC2222")
    };

    private boolean nightMode = false;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint anglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sensorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint alertArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint alertLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private List<Integer> alertDistancesMm = new ArrayList<>();

    private float cx, cy, scale;

    // Target data
    private final TargetData[] targets = new TargetData[3];
    @SuppressWarnings("unchecked")
    private final List<float[]>[] trails = new List[3];

    private long frameCount = 0;
    private long errorCount = 0;

    // FPS tracking
    private final List<Long> frameTimes = new ArrayList<>();
    private int currentFps = 0;

    // Listener for info updates
    public interface InfoListener {
        void onInfoUpdated(TargetData[] targets, long frameCount, long errorCount, int fps);
    }

    private InfoListener infoListener;

    public void setInfoListener(InfoListener listener) {
        this.infoListener = listener;
    }

    static class TargetData {
        boolean present;
        int x, y, speed, distance;
        float angle;
    }

    public RadarView(Context context) {
        super(context);
        init();
    }

    public RadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RadarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        for (int i = 0; i < 3; i++) {
            targets[i] = new TargetData();
            trails[i] = new ArrayList<>();
        }

        bgPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f);
        anglePaint.setStyle(Paint.Style.STROKE);
        anglePaint.setStrokeWidth(1f);
        labelPaint.setTextSize(28f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        sensorPaint.setStyle(Paint.Style.FILL);
        targetPaint.setStyle(Paint.Style.FILL);
        trailPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(28f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        alertArcPaint.setStyle(Paint.Style.STROKE);
        alertArcPaint.setStrokeWidth(2.5f);
        alertArcPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{12f, 8f}, 0f));

        alertLabelPaint.setTextSize(22f);
        alertLabelPaint.setTextAlign(Paint.Align.CENTER);
        alertLabelPaint.setFakeBoldText(true);

        arrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        arrowPaint.setStrokeWidth(4f);

        glowPaint.setStyle(Paint.Style.FILL);

        applyThemeColors();
    }

    /** Update paint colors based on the current theme (normal vs night mode). */
    private void applyThemeColors() {
        if (nightMode) {
            bgPaint.setColor(Color.parseColor("#0A0000"));
            ringPaint.setColor(Color.parseColor("#3A0000"));
            anglePaint.setColor(Color.parseColor("#220000"));
            labelPaint.setColor(Color.parseColor("#883333"));
            sensorPaint.setColor(Color.parseColor("#FF3333"));
            textPaint.setColor(Color.parseColor("#FF8888"));
            alertArcPaint.setColor(Color.parseColor("#FF8800"));
            alertLabelPaint.setColor(Color.parseColor("#FF8800"));
        } else {
            bgPaint.setColor(Color.parseColor("#0A0A1A"));
            ringPaint.setColor(Color.parseColor("#1A2A1A"));
            anglePaint.setColor(Color.parseColor("#152015"));
            labelPaint.setColor(Color.parseColor("#334433"));
            sensorPaint.setColor(Color.parseColor("#00FF88"));
            textPaint.setColor(Color.WHITE);
            alertArcPaint.setColor(Color.parseColor("#FFCC00"));
            alertLabelPaint.setColor(Color.parseColor("#FFCC00"));
        }
    }

    public void setNightMode(boolean enabled) {
        if (this.nightMode == enabled) return;
        this.nightMode = enabled;
        applyThemeColors();
        invalidate();
    }

    public boolean isNightMode() {
        return nightMode;
    }

    private int[] currentTargetColors() {
        return nightMode ? TARGET_COLORS_NIGHT : TARGET_COLORS_NORMAL;
    }

    /**
     * Render the current radar state into a Bitmap for snapshot/export. Caller owns
     * the returned bitmap and must recycle it when finished.
     */
    public Bitmap captureSnapshot() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        draw(c);
        return bmp;
    }

    /** Drop every target and clear all trails. Used when the device goes offline or
     *  no frame has arrived for a while — prevents the radar from showing stale
     *  positions that no longer correspond to anything in the real world. */
    public void clearTargets() {
        for (int i = 0; i < 3; i++) {
            targets[i].present = false;
            targets[i].x = 0;
            targets[i].y = 0;
            targets[i].speed = 0;
            targets[i].distance = 0;
            targets[i].angle = 0;
            if (trails[i] != null) trails[i].clear();
        }
        if (infoListener != null) {
            infoListener.onInfoUpdated(targets, frameCount, errorCount, 0);
        }
        invalidate();
    }

    public void updateTargets(JsonObject data) {
        // FPS tracking
        long now = System.currentTimeMillis();
        frameTimes.add(now);
        while (!frameTimes.isEmpty() && frameTimes.get(0) < now - 1000) {
            frameTimes.remove(0);
        }
        currentFps = frameTimes.size();

        JsonArray t = data.getAsJsonArray("t");
        if (t == null) return;

        frameCount = data.has("fc") ? data.get("fc").getAsLong() : 0;
        errorCount = data.has("ec") ? data.get("ec").getAsLong() : 0;

        for (int i = 0; i < Math.min(3, t.size()); i++) {
            JsonObject obj = t.get(i).getAsJsonObject();
            targets[i].present = obj.has("p") && obj.get("p").getAsBoolean();
            if (targets[i].present) {
                targets[i].x = obj.get("x").getAsInt();
                targets[i].y = obj.get("y").getAsInt();
                targets[i].speed = obj.get("s").getAsInt();
                targets[i].distance = obj.get("d").getAsInt();
                targets[i].angle = obj.has("a") ? obj.get("a").getAsFloat() : 0f;
            }
        }

        if (infoListener != null) {
            infoListener.onInfoUpdated(targets, frameCount, errorCount, currentFps);
        }

        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        // Fan radar: visible area is a half-circle (height ≈ width / 2).
        // Honor the bounded height when the parent constrains it (landscape side-by-side),
        // otherwise fall back to a square so portrait layouts keep their previous look.
        int size;
        if (heightMode == MeasureSpec.EXACTLY || heightMode == MeasureSpec.AT_MOST) {
            size = Math.min(width, height > 0 ? height * 2 : width);
        } else {
            size = width;
        }
        int finalWidth = size;
        int finalHeight = (heightMode == MeasureSpec.EXACTLY) ? height : Math.min(size, height > 0 ? height : size);
        setMeasuredDimension(finalWidth, finalHeight > 0 ? finalHeight : finalWidth);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h - h * 0.05f; // Sensor near bottom
        // Fit the fan into both width (half-circle radius = w/2) and height
        float maxRadiusByWidth = (w / 2f) - w * 0.02f;
        float maxRadiusByHeight = h - h * 0.1f;
        scale = Math.min(maxRadiusByWidth, maxRadiusByHeight) / MAX_RANGE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBackground(canvas);
        drawTargets(canvas);
    }

    public void setAlertDistancesMm(List<Integer> distances) {
        this.alertDistancesMm = distances == null ? new ArrayList<>() : distances;
        invalidate();
    }

    private void drawBackground(Canvas canvas) {
        // Fill background
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        // Range rings every 1m
        for (int r = 1000; r <= (int) MAX_RANGE; r += 1000) {
            float pr = r * scale;
            RectF oval = new RectF(cx - pr, cy - pr, cx + pr, cy + pr);
            canvas.drawArc(oval, 180, 180, false, ringPaint);

            // Range label
            canvas.drawText(r / 1000 + "m", cx + pr - 40, cy - 8, labelPaint);
        }

        // Alert distance arcs (yellow dashed) — drawn after the regular rings so they sit on top
        for (Integer d : alertDistancesMm) {
            if (d == null || d <= 0 || d > MAX_RANGE) continue;
            float pr = d * scale;
            RectF oval = new RectF(cx - pr, cy - pr, cx + pr, cy + pr);
            canvas.drawArc(oval, 180, 180, false, alertArcPaint);
            String label = (d % 1000 == 0) ? (d / 1000) + "m"
                                           : String.format(java.util.Locale.US, "%.1fm", d / 1000.0);
            canvas.drawText("⚠ " + label, cx, cy - pr - 6, alertLabelPaint);
        }

        // Angle lines: -60, -30, 0, +30, +60
        int[] angles = {-60, -30, 0, 30, 60};
        for (int a : angles) {
            double rad = Math.toRadians(a - 90);
            float ex = cx + (float) (Math.cos(rad) * MAX_RANGE * scale);
            float ey = cy + (float) (Math.sin(rad) * MAX_RANGE * scale);
            canvas.drawLine(cx, cy, ex, ey, anglePaint);

            // Angle label
            float lx = cx + (float) (Math.cos(rad) * (MAX_RANGE * scale + 30));
            float ly = cy + (float) (Math.sin(rad) * (MAX_RANGE * scale + 30));
            canvas.drawText(a + "\u00B0", lx, ly, labelPaint);
        }

        // Sensor dot
        canvas.drawCircle(cx, cy, 10, sensorPaint);
        Paint sensorLabel = new Paint(labelPaint);
        sensorLabel.setColor(nightMode ? Color.parseColor("#AA2222")
                                       : Color.parseColor("#00AA55"));
        sensorLabel.setTextSize(24f);
        canvas.drawText("SENSOR", cx, cy + 36, sensorLabel);
    }

    private void drawTargets(Canvas canvas) {
        int[] palette = currentTargetColors();
        for (int i = 0; i < 3; i++) {
            TargetData t = targets[i];
            if (!t.present) {
                trails[i].clear();
                continue;
            }

            // Convert mm to canvas pixels
            float px = cx + t.x * scale;
            float py = cy - t.y * scale;

            // Clamp to canvas
            px = Math.max(20, Math.min(getWidth() - 20, px));
            py = Math.max(20, Math.min(getHeight() - 20, py));

            int color = palette[i];

            // Update trail
            trails[i].add(new float[]{px, py});
            if (trails[i].size() > TRAIL_MAX) trails[i].remove(0);

            // Draw trail (fading)
            for (int j = 0; j < trails[i].size() - 1; j++) {
                float alpha = (j + 1f) / trails[i].size() * 0.4f;
                trailPaint.setColor(color);
                trailPaint.setAlpha((int) (alpha * 255));
                float sz = 6 + (j / (float) trails[i].size()) * 10;
                canvas.drawCircle(trails[i].get(j)[0], trails[i].get(j)[1], sz, trailPaint);
            }

            // Glow effect
            glowPaint.setColor(color);
            glowPaint.setAlpha(60);
            canvas.drawCircle(px, py, 32, glowPaint);

            // Main target dot
            targetPaint.setColor(color);
            targetPaint.setAlpha(255);
            canvas.drawCircle(px, py, 16, targetPaint);

            // Target label
            canvas.drawText("T" + (i + 1), px, py - 28, textPaint);

            // Speed arrow
            if (Math.abs(t.speed) > 1) {
                float arrowLen = Math.min(Math.abs(t.speed) * 4f, 60f);
                float dir = t.speed < 0 ? -1f : 1f;
                arrowPaint.setColor(color);
                arrowPaint.setAlpha(255);
                canvas.drawLine(px, py, px, py + dir * arrowLen, arrowPaint);

                // Arrowhead
                arrowPath.reset();
                arrowPath.moveTo(px, py + dir * arrowLen);
                arrowPath.lineTo(px - 8, py + dir * (arrowLen - 12));
                arrowPath.lineTo(px + 8, py + dir * (arrowLen - 12));
                arrowPath.close();
                canvas.drawPath(arrowPath, arrowPaint);
            }
        }
    }
}
