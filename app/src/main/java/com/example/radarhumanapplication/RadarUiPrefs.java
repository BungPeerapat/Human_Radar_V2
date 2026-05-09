package com.example.radarhumanapplication;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Lightweight wrapper around SharedPreferences for the Radar tab UX toggles.
 *
 * Persists:
 *  - keep screen on
 *  - lock orientation
 *  - night mode (red theme)
 *  - last manual rotation slot (0 = portrait, 1 = landscape)
 */
public class RadarUiPrefs {

    public static final String PREFS_NAME = "radar_ui_prefs";

    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String KEY_LOCK_ORIENTATION = "lock_orientation";
    public static final String KEY_NIGHT_MODE = "night_mode";
    public static final String KEY_MANUAL_ROTATION_SLOT = "manual_rotation_slot";

    private final SharedPreferences prefs;

    public RadarUiPrefs(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // Test-friendly constructor for direct injection of a SharedPreferences fake.
    RadarUiPrefs(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public boolean isKeepScreenOn() {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, false);
    }

    public void setKeepScreenOn(boolean value) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply();
    }

    public boolean isLockOrientation() {
        return prefs.getBoolean(KEY_LOCK_ORIENTATION, false);
    }

    public void setLockOrientation(boolean value) {
        prefs.edit().putBoolean(KEY_LOCK_ORIENTATION, value).apply();
    }

    public boolean isNightMode() {
        return prefs.getBoolean(KEY_NIGHT_MODE, false);
    }

    public void setNightMode(boolean value) {
        prefs.edit().putBoolean(KEY_NIGHT_MODE, value).apply();
    }

    public int getManualRotationSlot() {
        return prefs.getInt(KEY_MANUAL_ROTATION_SLOT, 0);
    }

    public void setManualRotationSlot(int slot) {
        prefs.edit().putInt(KEY_MANUAL_ROTATION_SLOT, slot).apply();
    }

    /**
     * Cycle the rotation slot: 0 (portrait) -> 1 (landscape) -> 0.
     * Pure helper — does not touch SharedPreferences. Caller persists the result.
     */
    public static int nextRotationSlot(int currentSlot) {
        return (currentSlot + 1) % 2;
    }
}
