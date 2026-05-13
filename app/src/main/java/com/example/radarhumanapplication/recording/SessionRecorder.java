package com.example.radarhumanapplication.recording;

import android.content.Context;
import android.util.Log;

import com.example.radarhumanapplication.MqttService;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Singleton recorder. Subscribes as a {@link MqttService.TargetListener} and writes each frame
 * to a JSON Lines file under {@code getExternalFilesDir("recordings")}. One line per frame:
 *
 * <pre>{"t": &lt;epoch_ms&gt;, "targets": [...]}</pre>
 */
public class SessionRecorder implements MqttService.TargetListener {

    private static final String TAG = "SessionRecorder";
    private static final String DIR = "recordings";
    private static final SessionRecorder INSTANCE = new SessionRecorder();

    public static SessionRecorder getInstance() { return INSTANCE; }

    private Context appContext;
    private BufferedWriter writer;
    private File currentFile;
    private long startedAtMs;
    private volatile boolean recording;

    private SessionRecorder() {}

    public synchronized boolean start(Context ctx) {
        if (recording) return true;
        if (ctx == null) return false;
        this.appContext = ctx.getApplicationContext();
        File dir = recordingsDir(this.appContext);
        if (dir == null) {
            Log.w(TAG, "External files dir unavailable");
            return false;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create recordings dir: " + dir);
            return false;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        // Tag the filename with the active device so the Replay list groups recordings
        // by source. Sanitise the device name (only [A-Za-z0-9_-], max 24 chars) so the
        // filename stays valid on every platform.
        String devTag = sanitiseDeviceName(MqttService.getInstance().getDeviceName());
        String name = "rec_" + fmt.format(new Date())
                + (devTag.isEmpty() ? "" : ("_" + devTag))
                + ".jsonl";
        File f = new File(dir, name);
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f, false), StandardCharsets.UTF_8));
            currentFile = f;
            startedAtMs = System.currentTimeMillis();
            recording = true;
            MqttService.getInstance().addTargetListener(this);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to open recording file", e);
            closeWriterQuietly();
            currentFile = null;
            return false;
        }
    }

    public synchronized void stop() {
        if (!recording) return;
        recording = false;
        try {
            MqttService.getInstance().removeTargetListener(this);
        } catch (Exception ignored) {}
        closeWriterQuietly();
    }

    public boolean isRecording() { return recording; }

    public File getCurrentFile() { return currentFile; }

    public long getStartedAtMs() { return startedAtMs; }

    public List<File> listRecordings() {
        if (appContext == null) return Collections.emptyList();
        File dir = recordingsDir(appContext);
        if (dir == null || !dir.exists()) return Collections.emptyList();
        File[] files = dir.listFiles((d, n) -> n != null && n.endsWith(".jsonl"));
        if (files == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        // Most recent first
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    /** Static variant so callers without prior start() can still list. */
    public List<File> listRecordings(Context ctx) {
        if (appContext == null && ctx != null) {
            this.appContext = ctx.getApplicationContext();
        }
        return listRecordings();
    }

    public boolean delete(File f) {
        if (f == null) return false;
        if (recording && currentFile != null && currentFile.equals(f)) {
            return false;
        }
        return f.delete();
    }

    @Override
    public void onTargetsReceived(JsonObject data) {
        if (!recording || writer == null) return;
        try {
            JsonObject line = new JsonObject();
            line.addProperty("t", System.currentTimeMillis());
            // The frame may include either "t" (target array) or "targets". We persist
            // with the canonical key "targets" alongside the timestamp.
            if (data.has("targets")) {
                line.add("targets", data.get("targets"));
            } else if (data.has("t")) {
                line.add("targets", data.get("t"));
            } else {
                line.add("targets", data);
            }
            synchronized (this) {
                if (writer == null) return;
                writer.write(line.toString());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, "Write frame failed", e);
        } catch (Exception e) {
            Log.w(TAG, "Frame serialize failed", e);
        }
    }

    private synchronized void closeWriterQuietly() {
        if (writer != null) {
            try { writer.flush(); } catch (IOException ignored) {}
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    static File recordingsDir(Context ctx) {
        File ext = ctx.getExternalFilesDir(DIR);
        if (ext != null) return ext;
        // Fallback to internal cache; useful in tests.
        return new File(ctx.getFilesDir(), DIR);
    }

    /** Strip everything but [A-Za-z0-9_-] and clamp to 24 chars so the device
     *  name can be safely embedded in a filename across platforms. */
    private static String sanitiseDeviceName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 24; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') sb.append(c);
        }
        return sb.toString();
    }
}
