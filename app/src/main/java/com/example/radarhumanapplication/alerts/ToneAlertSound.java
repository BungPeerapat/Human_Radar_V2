package com.example.radarhumanapplication.alerts;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;

/**
 * Emulates the ESP32 buzzer with {@link ToneGenerator}. Produces a clean
 * 1.5 kHz beep on the alarm stream so it stays audible in silent mode.
 */
public class ToneAlertSound implements AlertSoundSource {

    private static final String TAG = "ToneAlertSound";

    private ToneGenerator tone;
    private final int streamType;

    public ToneAlertSound(int volumePct, int streamType) {
        this.streamType = streamType;
        try {
            tone = new ToneGenerator(streamType, clampVolume(volumePct));
        } catch (RuntimeException e) {
            Log.w(TAG, "ToneGenerator init failed", e);
            tone = null;
        }
    }

    public void setVolume(int volumePct) {
        // ToneGenerator volume is set at construction. Recreate to apply.
        release();
        try {
            tone = new ToneGenerator(streamType, clampVolume(volumePct));
        } catch (RuntimeException e) {
            Log.w(TAG, "ToneGenerator re-init failed", e);
            tone = null;
        }
    }

    @Override public void onPatternStart(boolean longPrefix, int totalShorts, int currentCount) {
        // no-op: timing is handled by AlertPatternPlayer
    }

    @Override public void playShort(int durationMs) {
        emit(ToneGenerator.TONE_PROP_BEEP, durationMs);
    }

    @Override public void playLong(int durationMs) {
        emit(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, durationMs);
    }

    @Override public void release() {
        if (tone != null) {
            try { tone.release(); } catch (Exception ignored) {}
            tone = null;
        }
    }

    private void emit(int toneType, int durationMs) {
        if (tone == null) return;
        try {
            tone.startTone(toneType, durationMs);
        } catch (RuntimeException e) {
            Log.w(TAG, "startTone failed", e);
        }
    }

    private static int clampVolume(int pct) {
        if (pct < 0) return 0;
        if (pct > 100) return 100;
        return pct;
    }
}
