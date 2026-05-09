package com.example.radarhumanapplication.recording;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers for reading recordings written by {@link SessionRecorder}. Pure I/O so the
 * format stays test-friendly without Android dependencies.
 */
public final class RecordingFile {

    /** A single line in a JSONL recording. */
    public static final class Frame {
        public final long timestampMs;
        public final JsonElement targets;

        public Frame(long timestampMs, JsonElement targets) {
            this.timestampMs = timestampMs;
            this.targets = targets;
        }
    }

    private RecordingFile() {}

    public static List<Frame> read(File f) throws IOException {
        List<Frame> out = new ArrayList<>();
        Gson gson = new Gson();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                JsonObject obj = gson.fromJson(trimmed, JsonObject.class);
                if (obj == null) continue;
                long ts = obj.has("t") ? obj.get("t").getAsLong() : 0L;
                JsonElement targets = obj.has("targets") ? obj.get("targets") : null;
                out.add(new Frame(ts, targets));
            }
        }
        return out;
    }
}
