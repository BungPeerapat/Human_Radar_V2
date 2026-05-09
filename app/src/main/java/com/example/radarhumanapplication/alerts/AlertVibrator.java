package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

/**
 * Plays haptic vibration patterns for alert rules. Uses {@link VibratorManager} on API 31+
 * and falls back to the deprecated {@link Context#VIBRATOR_SERVICE} on older devices.
 *
 * Patterns map to: Short, Medium, Long, SOS (...---... morse).
 */
public class AlertVibrator {

    private static final String TAG = "AlertVibrator";

    // Public for testability (no Android dependency in tests).
    public static final long[] PATTERN_SHORT  = { 0, 200 };
    public static final long[] PATTERN_MEDIUM = { 0, 600 };
    public static final long[] PATTERN_LONG   = { 0, 1200 };
    /** SOS in morse: 3 short, 3 long, 3 short. waitOff/on alternation, leading 0. */
    public static final long[] PATTERN_SOS = {
            0,
            150, 100, 150, 100, 150, 250, // S
            450, 100, 450, 100, 450, 250, // O
            150, 100, 150, 100, 150       // S
    };

    private final Context appContext;
    private Vibrator vibrator;

    public AlertVibrator(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /** Resolve and cache the system Vibrator. */
    private Vibrator vibrator() {
        if (vibrator != null) return vibrator;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm =
                        (VibratorManager) appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vibrator = vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to resolve Vibrator", e);
        }
        return vibrator;
    }

    /** Play the rule's vibration pattern (no-op if rule.vibrate is false or no vibrator). */
    public void vibrate(AlertRule rule) {
        if (rule == null || !rule.vibrate) return;
        long[] pattern = patternFor(rule.vibrationPattern);
        play(pattern);
    }

    /** Play a named pattern directly (used by the editor's Test button). */
    public void vibratePattern(String name) {
        play(patternFor(name));
    }

    private void play(long[] pattern) {
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) return;
        try {
            VibrationEffect effect = VibrationEffect.createWaveform(pattern, -1);
            v.vibrate(effect);
        } catch (Exception e) {
            Log.w(TAG, "vibrate() failed", e);
        }
    }

    /** Cancel any ongoing vibration. */
    public void cancel() {
        Vibrator v = vibrator();
        if (v == null) return;
        try { v.cancel(); } catch (Exception ignored) {}
    }

    /** Resolve a pattern by name. Defaults to {@link #PATTERN_SHORT}. */
    public static long[] patternFor(String name) {
        if (name == null) return PATTERN_SHORT;
        switch (name) {
            case AlertRule.VIB_MEDIUM: return PATTERN_MEDIUM;
            case AlertRule.VIB_LONG:   return PATTERN_LONG;
            case AlertRule.VIB_SOS:    return PATTERN_SOS;
            case AlertRule.VIB_SHORT:
            default:                   return PATTERN_SHORT;
        }
    }
}
