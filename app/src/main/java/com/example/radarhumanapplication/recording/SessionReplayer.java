package com.example.radarhumanapplication.recording;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.radarhumanapplication.MqttService;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton replayer for JSONL recordings produced by {@link SessionRecorder}.
 *
 * <p>Replays at original timing, optionally scaled by {@code speed} (e.g. 0.5 / 1.0 / 2.0). Each
 * replayed frame is delivered to every {@link MqttService.TargetListener} currently registered on
 * {@link MqttService}, so the radar canvas, alerts and dashboard react as if the data were live.
 *
 * <p><b>Caveat:</b> live MQTT messages are <i>not</i> paused while replay runs (we do not modify
 * MqttService). If MQTT is connected during replay, live frames and replay frames will interleave
 * on the radar UI. Disconnect MQTT before replay if you want a clean playback.
 */
public class SessionReplayer {

    private static final String TAG = "SessionReplayer";
    private static final SessionReplayer INSTANCE = new SessionReplayer();

    public static SessionReplayer getInstance() { return INSTANCE; }

    /** Listener invoked whenever a replay transitions in/out of the active state. */
    public interface StateListener {
        void onReplayStateChanged(boolean replaying, String fileName, double speed);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    private List<RecordingFile.Frame> frames = Collections.emptyList();
    private int index = 0;
    private double speed = 1.0;
    private long playbackBaseRealtimeMs;
    private long firstFrameTsMs;
    private volatile boolean replaying;
    private volatile boolean paused;
    private long pausedElapsedMs;
    private String currentFileName = "";

    private SessionReplayer() {}

    public void addStateListener(StateListener l)    { if (l != null) listeners.add(l); }
    public void removeStateListener(StateListener l) { listeners.remove(l); }
    public String getCurrentFileName() { return currentFileName; }
    public double getCurrentSpeed()    { return speed; }

    /** If true, live MQTT TargetListener fan-out is muted while replay runs. */
    private volatile boolean autoMuteLiveMqtt = true;
    /** Recorded so we can re-enable live MQTT on stop() without flipping unrelated state. */
    private boolean mutedMqttForThisRun = false;

    public void setAutoMuteLiveMqtt(boolean enabled) { this.autoMuteLiveMqtt = enabled; }
    public boolean isAutoMuteLiveMqtt() { return autoMuteLiveMqtt; }

    public synchronized boolean loadAndStart(File f, double speed) {
        stop();
        if (f == null || !f.exists()) return false;
        try {
            frames = RecordingFile.read(f);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read recording", e);
            frames = Collections.emptyList();
            return false;
        }
        if (frames.isEmpty()) return false;
        this.speed = speed > 0 ? speed : 1.0;
        this.index = 0;
        this.firstFrameTsMs = frames.get(0).timestampMs;
        this.playbackBaseRealtimeMs = System.currentTimeMillis();
        this.replaying = true;
        this.paused = false;
        this.pausedElapsedMs = 0;
        this.currentFileName = f.getName();
        if (autoMuteLiveMqtt) {
            com.example.radarhumanapplication.MqttService.getInstance().setLiveTargetMute(true);
            mutedMqttForThisRun = true;
        } else {
            mutedMqttForThisRun = false;
        }
        notifyState();
        scheduleNext();
        return true;
    }

    public synchronized void pause() {
        if (!replaying || paused) return;
        paused = true;
        pausedElapsedMs = System.currentTimeMillis() - playbackBaseRealtimeMs;
        main.removeCallbacksAndMessages(null);
    }

    public synchronized void resume() {
        if (!replaying || !paused) return;
        paused = false;
        playbackBaseRealtimeMs = System.currentTimeMillis() - pausedElapsedMs;
        scheduleNext();
    }

    public synchronized void stop() {
        boolean wasReplaying = replaying;
        replaying = false;
        paused = false;
        index = 0;
        frames = Collections.emptyList();
        main.removeCallbacksAndMessages(null);
        if (mutedMqttForThisRun) {
            com.example.radarhumanapplication.MqttService.getInstance().setLiveTargetMute(false);
            mutedMqttForThisRun = false;
        }
        if (wasReplaying) {
            currentFileName = "";
            notifyState();
        }
    }

    public boolean isReplaying() { return replaying; }

    public boolean isPaused() { return paused; }

    public synchronized double getProgress() {
        if (frames.isEmpty()) return 0.0;
        if (!replaying) return 0.0;
        if (index >= frames.size()) return 1.0;
        return (double) index / frames.size();
    }

    public int getFrameCount() { return frames.size(); }
    public synchronized int getCurrentFrameIndex() { return index; }

    /** Seek to an arbitrary frame index. Pauses, jumps, and (if currently playing) resumes. */
    public synchronized void seekTo(int targetIndex) {
        if (frames.isEmpty()) return;
        int clamped = Math.max(0, Math.min(frames.size() - 1, targetIndex));
        boolean wasPlaying = replaying && !paused;
        main.removeCallbacksAndMessages(null);
        this.index = clamped;
        this.firstFrameTsMs = frames.get(0).timestampMs;
        long offset = (long) ((frames.get(clamped).timestampMs - firstFrameTsMs) / speed);
        this.playbackBaseRealtimeMs = System.currentTimeMillis() - offset;
        if (wasPlaying) {
            paused = false;
            scheduleNext();
        } else {
            paused = true;
        }
    }

    /** Per-recording bookmarks (transient — cleared on stop). */
    private final java.util.List<Integer> bookmarks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public java.util.List<Integer> getBookmarks() {
        return new java.util.ArrayList<>(bookmarks);
    }

    /** Toggle a bookmark at the current frame index. Returns true if added, false if removed. */
    public synchronized boolean toggleBookmarkHere() {
        Integer key = Integer.valueOf(index);
        if (bookmarks.contains(key)) {
            bookmarks.remove(key);
            return false;
        }
        bookmarks.add(key);
        java.util.Collections.sort(bookmarks);
        return true;
    }

    private void scheduleNext() {
        if (!replaying || paused) return;
        if (index >= frames.size()) {
            replaying = false;
            currentFileName = "";
            notifyState();
            return;
        }
        RecordingFile.Frame frame = frames.get(index);
        long offset = (long) ((frame.timestampMs - firstFrameTsMs) / speed);
        long target = playbackBaseRealtimeMs + offset;
        long delay = Math.max(0, target - System.currentTimeMillis());
        main.postDelayed(this::deliverNext, delay);
    }

    private void deliverNext() {
        RecordingFile.Frame frame;
        boolean justFinished = false;
        synchronized (this) {
            if (!replaying || paused) return;
            if (index >= frames.size()) {
                replaying = false;
                currentFileName = "";
                justFinished = true;
                frame = null;
            } else {
                frame = frames.get(index);
                index++;
            }
        }
        if (justFinished) {
            notifyState();
            return;
        }
        try {
            JsonObject data = new JsonObject();
            // Mirror live shape: targets under "t" (matches firmware payload + DashboardFragment)
            // and also under "targets" so AlertManager and other consumers using the long key
            // continue to work. Both reference the same JsonElement.
            if (frame.targets != null) {
                data.add("t", frame.targets);
                data.add("targets", frame.targets);
            }
            data.addProperty("replay", true);
            dispatch(data);
        } catch (Exception e) {
            Log.w(TAG, "Replay deliver failed", e);
        }
        scheduleNext();
    }

    private void notifyState() {
        final boolean state = replaying;
        final String  name  = currentFileName;
        final double  spd   = speed;
        main.post(() -> {
            for (StateListener l : listeners) {
                try { l.onReplayStateChanged(state, name, spd); }
                catch (Exception ignored) {}
            }
        });
    }

    /**
     * Deliver to every {@link MqttService.TargetListener} registered on the singleton. Uses
     * reflection because MqttService does not expose a dispatch API. If reflection fails, falls
     * back to a no-op (with logged warning).
     */
    @SuppressWarnings("unchecked")
    private void dispatch(JsonObject data) {
        try {
            MqttService svc = MqttService.getInstance();
            Field f = MqttService.class.getDeclaredField("targetListeners");
            f.setAccessible(true);
            Object value = f.get(svc);
            if (value instanceof Iterable) {
                for (Object l : (Iterable<Object>) value) {
                    if (l instanceof MqttService.TargetListener) {
                        try {
                            ((MqttService.TargetListener) l).onTargetsReceived(data);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.w(TAG, "Cannot dispatch replay frames (reflection failed): " + e.getMessage());
        }
    }
}
