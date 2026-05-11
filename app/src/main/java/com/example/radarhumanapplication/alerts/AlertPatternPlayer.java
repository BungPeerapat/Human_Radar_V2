package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Android-side mirror of the ESP32 {@code AlertPattern} state machine.
 *
 * <p>Receives a stream of target counts (already filtered for valid x/y and
 * within range), applies the same {@code debounceMs} window, then plays:</p>
 *
 * <ul>
 *   <li><b>Increase</b> ({@code prev < now}): {@code now} short beeps</li>
 *   <li><b>Decrease &gt; 0</b>: 1 long beep + {@code now} short beeps</li>
 *   <li><b>Decrease to 0</b>: 1 long beep only</li>
 * </ul>
 *
 * <p>While a pattern is playing, target-count changes are recorded but not
 * acted on; the next pattern is started after the current one fully completes,
 * comparing the last <i>played</i> count to the latest <i>applied</i> count.</p>
 */
public class AlertPatternPlayer {

    private static final String TAG = "AlertPatternPlayer";

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private AlertPatternConfig config = new AlertPatternConfig();
    private AlertSoundSource sound;

    // Debounce state
    private int     appliedCount    = 0;
    private int     playedCount     = 0;
    private int     candidateCount  = 0;
    private long    candidateSince  = 0;
    private boolean candidateActive = false;

    // Playback state
    private boolean playing = false;

    public AlertPatternPlayer(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.sound = buildSound(config);
    }

    public synchronized void setConfig(AlertPatternConfig newConfig) {
        if (newConfig == null) return;
        boolean soundChanged = (this.config.soundType != newConfig.soundType)
                            || (this.config.volumePct != newConfig.volumePct)
                            || !same(this.config.shortSoundUri, newConfig.shortSoundUri)
                            || !same(this.config.longSoundUri,  newConfig.longSoundUri);
        this.config = newConfig.copy();
        if (soundChanged) {
            if (sound != null) sound.release();
            sound = buildSound(this.config);
        }
    }

    private static boolean same(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    public AlertPatternConfig getConfig() { return config.copy(); }

    /**
     * Feed in the raw active-target count for the latest frame.
     * The caller is responsible for applying the valid-XY and {@code maxRangeMm}
     * filters (see {@link #countActive(JsonObject, int)}).
     */
    public synchronized void onTargetCount(int count) {
        if (!config.alertEnabled) {
            appliedCount    = count;
            playedCount     = count;
            candidateActive = false;
            return;
        }

        final long now = System.currentTimeMillis();

        if (count != appliedCount) {
            if (!candidateActive || count != candidateCount) {
                candidateCount  = count;
                candidateSince  = now;
                candidateActive = true;
                return;
            }
            if (now - candidateSince >= config.debounceMs) {
                int prev        = appliedCount;
                appliedCount    = candidateCount;
                candidateActive = false;
                if (!playing) {
                    startPattern(prev, appliedCount);
                }
            }
        } else {
            candidateActive = false;
        }
    }

    /** Test trigger (used by the "Test on phone" button). */
    public synchronized void triggerTest(int shortCount, boolean longPrefix) {
        if (playing) {
            Log.w(TAG, "Test ignored: pattern busy");
            return;
        }
        playSchedule(longPrefix, shortCount, Math.max(shortCount, longPrefix ? 1 : 0));
    }

    public synchronized void shutdown() {
        handler.removeCallbacksAndMessages(null);
        if (sound != null) sound.release();
        sound = null;
    }

    // ------------------------------------------------------------------
    //  Internals
    // ------------------------------------------------------------------
    private void startPattern(int prev, int now) {
        if (!config.alertEnabled) return;
        if (prev == now) return;

        boolean longPrefix;
        int     shorts;
        if (now > prev) {
            longPrefix = false;
            shorts     = now;
        } else if (now == 0) {
            longPrefix = true;
            shorts     = 0;
        } else {
            longPrefix = true;
            shorts     = now;
        }
        Log.i(TAG, "Pattern prev=" + prev + " now=" + now + " long=" + longPrefix + " shorts=" + shorts);
        playSchedule(longPrefix, shorts, now);
        playedCount = appliedCount;
    }

    /** Build a list of timed callbacks for the requested pattern. */
    private void playSchedule(boolean longPrefix, int shortCount, int targetCountAtStart) {
        if (sound == null) return;
        playing = true;
        sound.onPatternStart(longPrefix, shortCount, targetCountAtStart);

        long t = 0;
        if (longPrefix) {
            final long at = t;
            handler.postDelayed(() -> { if (sound != null) sound.playLong(config.beepLongMs); }, at);
            t += config.beepLongMs + config.beepGapMs;
        }
        for (int i = 0; i < shortCount; i++) {
            final long at = t;
            handler.postDelayed(() -> { if (sound != null) sound.playShort(config.beepShortMs); }, at);
            t += config.beepShortMs + config.beepGapMs;
        }
        // Pattern done marker (slightly after the last beep finishes)
        handler.postDelayed(this::onPatternComplete, Math.max(t, 1L));
    }

    private synchronized void onPatternComplete() {
        playing = false;
        // If the target count drifted while we were playing, kick off a follow-up.
        if (playedCount != appliedCount) {
            int prev    = playedCount;
            playedCount = appliedCount;
            startPattern(prev, appliedCount);
        }
    }

    private AlertSoundSource buildSound(AlertPatternConfig c) {
        switch (c.soundType) {
            case WAV:  return new WavAlertSound(appContext, c.volumePct, c.shortSoundUri, c.longSoundUri);
            case TTS:  return new TtsAlertSound(appContext);
            case TONE:
            default:   return new ToneAlertSound(c.volumePct, AudioManager.STREAM_ALARM);
        }
    }

    // ------------------------------------------------------------------
    //  Static helper: extract active target count from a targets JSON.
    //  Schema: {"t":[{x,y,s,d,a,p}, ...], "fc":..., "ec":...}
    // ------------------------------------------------------------------
    public static int countActive(JsonObject targetsPayload, int maxRangeMm) {
        if (targetsPayload == null || !targetsPayload.has("t")) return 0;
        JsonArray arr = targetsPayload.getAsJsonArray("t");
        int n = 0;
        for (int i = 0; i < arr.size(); i++) {
            JsonObject t = arr.get(i).getAsJsonObject();
            boolean present = t.has("p") && t.get("p").getAsBoolean();
            if (!present) continue;
            int x = t.has("x") ? t.get("x").getAsInt() : 0;
            int y = t.has("y") ? t.get("y").getAsInt() : 0;
            if (x == 0 && y == 0) continue;
            int d = t.has("d") ? t.get("d").getAsInt() : 0;
            if (maxRangeMm > 0 && d > maxRangeMm) continue;
            n++;
        }
        return n;
    }
}
