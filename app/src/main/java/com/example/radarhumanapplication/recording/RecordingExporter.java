package com.example.radarhumanapplication.recording;

import android.content.Context;
import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Converts JSONL recordings into a flat CSV (one row per target slot per frame) and writes the
 * result to a destination chosen by the user via SAF — works whether the user picked a file in
 * Downloads, Drive, an SD card, etc.
 */
public final class RecordingExporter {

    private RecordingExporter() {}

    /** Columns:  timestamp_ms,frame_idx,slot,present,x_mm,y_mm,speed_cms,distance_mm,angle_deg */
    public static int writeCsv(Context ctx, File source, Uri dest) throws Exception {
        List<RecordingFile.Frame> frames = RecordingFile.read(source);
        int rows = 0;
        try (OutputStream os = ctx.getContentResolver().openOutputStream(dest, "w");
             Writer w = new BufferedWriter(
                     new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            if (os == null) throw new IllegalStateException("OutputStream unavailable for " + dest);
            w.write("timestamp_ms,frame_idx,slot,present,x_mm,y_mm,speed_cms,distance_mm,angle_deg\n");
            int frameIdx = 0;
            for (RecordingFile.Frame f : frames) {
                if (f.targets == null || !f.targets.isJsonArray()) { frameIdx++; continue; }
                JsonArray arr = f.targets.getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    if (!arr.get(i).isJsonObject()) continue;
                    JsonObject t = arr.get(i).getAsJsonObject();
                    boolean present = (t.has("p") && t.get("p").getAsBoolean())
                            || (t.has("present") && t.get("present").getAsBoolean());
                    int x   = intOrZero(t, "x");
                    int y   = intOrZero(t, "y");
                    int spd = intOrZero(t, "s", "speed");
                    int dst = intOrZero(t, "d", "distance");
                    double ang = doubleOrZero(t, "a", "angle");
                    w.write(String.format(Locale.US,
                            "%d,%d,%d,%s,%d,%d,%d,%d,%.2f%n",
                            f.timestampMs, frameIdx, i, present ? "1" : "0",
                            x, y, spd, dst, ang));
                    rows++;
                }
                frameIdx++;
            }
            w.flush();
        }
        return rows;
    }

    private static int intOrZero(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try { return o.get(k).getAsInt(); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static double doubleOrZero(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try { return o.get(k).getAsDouble(); } catch (Exception ignored) {}
            }
        }
        return 0.0;
    }
}
