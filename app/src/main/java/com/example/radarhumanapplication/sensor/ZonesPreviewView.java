package com.example.radarhumanapplication.sensor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standalone preview canvas for the Detection-Zones editor dialog.
 *
 * <p>Draws a simplified top-down radar plot: a ±6 m horizontal field, a
 * 0–6 m forward fan, dashed range arcs at 1, 3, and 6 metres, and a dashed
 * coloured rectangle for every enabled zone. The coordinate system matches
 * the firmware convention used in {@code main.cpp}: world X is left/right
 * in millimetres (negative = left), world Y is forward distance in mm
 * (positive = away from the sensor). The sensor itself sits at the
 * bottom-centre of the view.
 *
 * <p>The view is fully standalone: it shares no state with the live
 * {@code RadarView}. Set zones via {@link #setZones(List)} and the view
 * invalidates itself.
 */
public class ZonesPreviewView extends View {

    /** Plain-data zone used by the preview. */
    public static final class Zone {
        public final boolean enabled;
        public final int x1, y1, x2, y2;

        public Zone(boolean enabled, int x1, int y1, int x2, int y2) {
            this.enabled = enabled;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    // World extents in millimetres. ±6000 mm wide × 0–6000 mm deep covers
    // the full LD2450 effective fan with a small safety margin.
    private static final float WORLD_HALF_WIDTH_MM = 6000f;
    private static final float WORLD_DEPTH_MM      = 6000f;
    private static final float PADDING_PX          = 8f;

    private static final int[] ZONE_COLORS = {
            Color.parseColor("#FF00FF88"),   // green  (zone 0)
            Color.parseColor("#FFFFCC00"),   // yellow (zone 1)
            Color.parseColor("#FF88CCFF"),   // cyan/blue (zone 2)
    };

    private final Paint fanPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fanFillPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zonePaint       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sensorPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint       = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Zone> zones = Collections.emptyList();

    // Cached transform — recomputed in onSizeChanged.
    private float originX = 0f;        // pixel X of world (0,0) — sensor
    private float originY = 0f;        // pixel Y of world (0,0) — sensor
    private float mmPerPxX = 1f;       // world mm per pixel along X
    private float mmPerPxY = 1f;       // world mm per pixel along Y

    public ZonesPreviewView(Context context) {
        super(context);
        init();
    }

    public ZonesPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZonesPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        fanPaint.setStyle(Paint.Style.STROKE);
        fanPaint.setColor(Color.parseColor("#FF1A4A6A"));
        fanPaint.setStrokeWidth(1.5f);

        fanFillPaint.setStyle(Paint.Style.FILL);
        fanFillPaint.setColor(Color.parseColor("#0A88CCFF"));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setColor(Color.parseColor("#FF1A2A3A"));
        arcPaint.setStrokeWidth(1f);
        arcPaint.setPathEffect(new DashPathEffect(new float[]{6f, 6f}, 0));

        zonePaint.setStyle(Paint.Style.STROKE);
        zonePaint.setStrokeWidth(2f);
        zonePaint.setPathEffect(new DashPathEffect(new float[]{8f, 6f}, 0));

        sensorPaint.setStyle(Paint.Style.FILL);
        sensorPaint.setColor(Color.parseColor("#FF00FF88"));

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setColor(Color.parseColor("#FF26334A"));
        axisPaint.setStrokeWidth(1f);
    }

    /** Replace the zone list and trigger a redraw. Null is treated as empty. */
    public void setZones(@Nullable List<Zone> incoming) {
        zones = incoming == null ? Collections.emptyList() : new ArrayList<>(incoming);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeTransform(w, h);
    }

    private void recomputeTransform(int w, int h) {
        float usableW = Math.max(1f, w - 2f * PADDING_PX);
        float usableH = Math.max(1f, h - 2f * PADDING_PX);

        // Fit the full 2·WORLD_HALF_WIDTH_MM × WORLD_DEPTH_MM rectangle inside
        // the usable area. We use a single isotropic scale so circles stay
        // circular and rectangles aren't stretched.
        float scaleX = usableW / (2f * WORLD_HALF_WIDTH_MM);
        float scaleY = usableH / WORLD_DEPTH_MM;
        float scale  = Math.min(scaleX, scaleY);

        mmPerPxX = 1f / scale;
        mmPerPxY = 1f / scale;

        float worldPxWidth  = 2f * WORLD_HALF_WIDTH_MM * scale;
        float worldPxHeight = WORLD_DEPTH_MM * scale;

        // Sensor (world origin) is bottom-centre of the fitted area.
        originX = (w - worldPxWidth) / 2f + worldPxWidth / 2f;
        originY = (h - worldPxHeight) / 2f + worldPxHeight;
    }

    private float worldXtoPx(float xMm) {
        return originX + xMm / mmPerPxX;
    }

    /** World Y points away from the sensor — bigger Y means further forward
     * which on screen means upward, so we subtract. */
    private float worldYtoPx(float yMm) {
        return originY - yMm / mmPerPxY;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (getWidth() == 0 || getHeight() == 0) return;

        drawFan(canvas);
        drawRangeArcs(canvas);
        drawCentreAxis(canvas);
        drawZones(canvas);
        drawSensor(canvas);
    }

    private void drawFan(Canvas canvas) {
        // Approximate the LD2450 field of view (~±60°) as a triangular fan
        // from the sensor out to 6 m. This is purely a visual hint.
        float leftX  = worldXtoPx(-WORLD_HALF_WIDTH_MM);
        float rightX = worldXtoPx(WORLD_HALF_WIDTH_MM);
        float topY   = worldYtoPx(WORLD_DEPTH_MM);

        Path fan = new Path();
        fan.moveTo(originX, originY);
        fan.lineTo(leftX, topY);
        fan.lineTo(rightX, topY);
        fan.close();
        canvas.drawPath(fan, fanFillPaint);
        canvas.drawPath(fan, fanPaint);
    }

    private void drawRangeArcs(Canvas canvas) {
        float[] rangesMm = {1000f, 3000f, 6000f};
        for (float r : rangesMm) {
            float radiusPx = r / mmPerPxY;
            // Use an arc clipped to the upper half so we don't draw below the sensor.
            RectF oval = new RectF(
                    originX - radiusPx,
                    originY - radiusPx,
                    originX + radiusPx,
                    originY + radiusPx);
            // sweep from 180° (left) to 360° (right) i.e. only the top half
            canvas.drawArc(oval, 180f, 180f, false, arcPaint);
        }
    }

    private void drawCentreAxis(Canvas canvas) {
        float topY = worldYtoPx(WORLD_DEPTH_MM);
        canvas.drawLine(originX, originY, originX, topY, axisPaint);
    }

    private void drawZones(Canvas canvas) {
        if (zones.isEmpty()) return;
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            if (z == null || !z.enabled) continue;
            int color = ZONE_COLORS[i % ZONE_COLORS.length];
            zonePaint.setColor(color);

            float xMin = Math.min(z.x1, z.x2);
            float xMax = Math.max(z.x1, z.x2);
            float yMin = Math.min(z.y1, z.y2);
            float yMax = Math.max(z.y1, z.y2);

            float left   = worldXtoPx(xMin);
            float right  = worldXtoPx(xMax);
            float bottom = worldYtoPx(yMin);   // smaller Y -> closer to sensor -> lower on screen? No: y small = closer = nearer bottom
            float top    = worldYtoPx(yMax);

            // worldYtoPx maps larger y to smaller pixel-y (upward). So:
            //   yMax (further) -> smaller pixel-y -> top
            //   yMin (closer)  -> larger pixel-y  -> bottom
            // The two assignments above are already correct for that mapping.
            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRect(rect, zonePaint);
        }
    }

    private void drawSensor(Canvas canvas) {
        canvas.drawCircle(originX, originY, 4f, sensorPaint);
    }
}
