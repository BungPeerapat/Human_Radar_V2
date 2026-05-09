package com.example.radarhumanapplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for {@link RadarUiPrefs}. Robolectric is not on this project's test
 * classpath, so we exercise {@code RadarUiPrefs} via a hand-written
 * {@link SharedPreferences} fake plus the pure-Java helper {@code nextRotationSlot}.
 */
public class RadarUiPrefsTest {

    private FakeSharedPreferences fake;
    private RadarUiPrefs prefs;

    @Before
    public void setUp() {
        fake = new FakeSharedPreferences();
        prefs = new RadarUiPrefs(fake);
    }

    @Test
    public void keepScreenOn_defaultsFalse_andRoundTrips() {
        assertFalse("default should be off", prefs.isKeepScreenOn());
        prefs.setKeepScreenOn(true);
        assertTrue(prefs.isKeepScreenOn());
        prefs.setKeepScreenOn(false);
        assertFalse(prefs.isKeepScreenOn());
    }

    @Test
    public void lockOrientation_defaultsFalse_andRoundTrips() {
        assertFalse(prefs.isLockOrientation());
        prefs.setLockOrientation(true);
        assertTrue(prefs.isLockOrientation());
        prefs.setLockOrientation(false);
        assertFalse(prefs.isLockOrientation());
    }

    @Test
    public void nightMode_defaultsFalse_andRoundTrips() {
        assertFalse(prefs.isNightMode());
        prefs.setNightMode(true);
        assertTrue(prefs.isNightMode());
        prefs.setNightMode(false);
        assertFalse(prefs.isNightMode());
    }

    @Test
    public void manualRotationSlot_defaultsZero_andRoundTrips() {
        assertEquals(0, prefs.getManualRotationSlot());
        prefs.setManualRotationSlot(1);
        assertEquals(1, prefs.getManualRotationSlot());
        prefs.setManualRotationSlot(0);
        assertEquals(0, prefs.getManualRotationSlot());
    }

    @Test
    public void nextRotationSlot_cyclesPortraitLandscapePortrait() {
        assertEquals(1, RadarUiPrefs.nextRotationSlot(0));
        assertEquals(0, RadarUiPrefs.nextRotationSlot(1));
        // Defensively: any unexpected value still maps into the 2-slot cycle
        assertEquals(1, RadarUiPrefs.nextRotationSlot(2));
        assertEquals(0, RadarUiPrefs.nextRotationSlot(3));
    }

    @Test
    public void prefsKeys_areStable() {
        // Lock down keys so historical data on user devices keeps working.
        assertEquals("keep_screen_on", RadarUiPrefs.KEY_KEEP_SCREEN_ON);
        assertEquals("lock_orientation", RadarUiPrefs.KEY_LOCK_ORIENTATION);
        assertEquals("night_mode", RadarUiPrefs.KEY_NIGHT_MODE);
        assertEquals("manual_rotation_slot", RadarUiPrefs.KEY_MANUAL_ROTATION_SLOT);
        assertEquals("radar_ui_prefs", RadarUiPrefs.PREFS_NAME);
    }

    /**
     * Minimal in-memory SharedPreferences for unit tests. Only the methods used by
     * {@link RadarUiPrefs} are wired up; everything else throws to surface accidental
     * usage instead of returning misleading defaults.
     */
    private static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> store = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(store); }
        @Override public String getString(String key, String defValue) {
            Object v = store.get(key); return v instanceof String ? (String) v : defValue;
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            throw new UnsupportedOperationException();
        }
        @Override public int getInt(String key, int defValue) {
            Object v = store.get(key); return v instanceof Integer ? (Integer) v : defValue;
        }
        @Override public long getLong(String key, long defValue) {
            Object v = store.get(key); return v instanceof Long ? (Long) v : defValue;
        }
        @Override public float getFloat(String key, float defValue) {
            Object v = store.get(key); return v instanceof Float ? (Float) v : defValue;
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object v = store.get(key); return v instanceof Boolean ? (Boolean) v : defValue;
        }
        @Override public boolean contains(String key) { return store.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> staged = new HashMap<>();
                private boolean clear = false;
                @Override public Editor putString(String key, String value) { staged.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> values) { staged.put(key, values); return this; }
                @Override public Editor putInt(String key, int value) { staged.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { staged.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { staged.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { staged.put(key, value); return this; }
                @Override public Editor remove(String key) { staged.put(key, null); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override
                public void apply() {
                    if (clear) store.clear();
                    for (Map.Entry<String, Object> e : staged.entrySet()) {
                        if (e.getValue() == null) store.remove(e.getKey());
                        else store.put(e.getKey(), e.getValue());
                    }
                }
            };
        }
    }
}
