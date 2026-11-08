package com.example.radarhumanapplication;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.example.radarhumanapplication.update.UpdateInfo;
import com.google.gson.JsonParser;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the guard with real pointer events, including interrupted and completed holds. */
@RunWith(AndroidJUnit4.class)
public class RadarTouchLockInstrumentedTest {
    private int id(MainActivity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private int[] center(View view) {
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);
        xy[0] += view.getWidth() / 2;
        xy[1] += view.getHeight() / 2;
        return xy;
    }

    private void pointer(int action, int[] xy, long downTime) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                action, xy[0], xy[1], 0);
        try {
            InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
        } finally {
            event.recycle();
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void tap(int[] xy) {
        long time = SystemClock.uptimeMillis();
        pointer(MotionEvent.ACTION_DOWN, xy, time);
        pointer(MotionEvent.ACTION_UP, xy, time);
    }

    @Test
    public void physicalTouchLockTapDisablesNavigationAndRadarActions() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            int[][] positions = new int[3][];
            int[] orientation = new int[1];
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.btn_lock_touch).getWidth() > 0);
                positions[0] = center(activity.findViewById(R.id.btn_lock_touch));
                positions[1] = center(activity.findViewById(R.id.nav_dashboard));
                positions[2] = center(activity.findViewById(R.id.radar_view));
                orientation[0] = activity.getRequestedOrientation();
            });
            tap(positions[0]);
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.radar_touch_lock_overlay).getVisibility());
                for (int control : new int[]{R.id.btn_manual_rotate, R.id.btn_lock_orientation,
                        R.id.btn_keep_screen_on, R.id.btn_night_mode, R.id.btn_snapshot,
                        R.id.btn_multi_device, R.id.radar_view, R.id.nav_dashboard}) {
                    assertFalse("Locked controls must really be disabled: " + control,
                            activity.findViewById(control).isEnabled());
                }
                // Commands must also be guarded if a click is invoked outside touch dispatch.
                activity.findViewById(R.id.btn_manual_rotate).performClick();
                activity.findViewById(R.id.btn_snapshot).performClick();
                ((BottomNavigationView) activity.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_dashboard);
                assertEquals(orientation[0], activity.getRequestedOrientation());
                assertEquals(R.id.nav_radar, ((BottomNavigationView) activity.findViewById(R.id.bottom_nav)).getSelectedItemId());
            });
            tap(positions[1]);
            long time = SystemClock.uptimeMillis();
            pointer(MotionEvent.ACTION_DOWN, positions[2], time);
            SystemClock.sleep(2200);
            pointer(MotionEvent.ACTION_UP, positions[2], time);
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.radar_touch_lock_overlay).getVisibility());
                assertTrue(activity.findViewById(R.id.btn_manual_rotate).isEnabled());
                assertTrue(activity.findViewById(R.id.nav_dashboard).isEnabled());
            });
            tap(positions[1]);
            scenario.onActivity(activity -> assertEquals(R.id.nav_dashboard,
                    ((BottomNavigationView) activity.findViewById(R.id.bottom_nav)).getSelectedItemId()));
        }
    }

    @Test
    public void controlFenceRestoresOriginalStatesAndDisablesLateChildren() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                android.widget.FrameLayout content = new android.widget.FrameLayout(activity);
                android.widget.Button initiallyDisabled = new android.widget.Button(activity);
                initiallyDisabled.setEnabled(false);
                android.widget.Button initiallyEnabled = new android.widget.Button(activity);
                content.addView(initiallyDisabled);
                content.addView(initiallyEnabled);
                RadarTouchLockController controller = new RadarTouchLockController(content,
                        new android.widget.FrameLayout(activity), new TextView(activity), locked -> {});
                try {
                    controller.setLocked(true);
                    assertFalse(initiallyEnabled.isEnabled());
                    android.widget.Button late = new android.widget.Button(activity);
                    content.addView(late);
                    content.getViewTreeObserver().dispatchOnGlobalLayout();
                    assertFalse(late.isEnabled());
                    controller.setLocked(false);
                    assertFalse(initiallyDisabled.isEnabled());
                    assertTrue(initiallyEnabled.isEnabled());
                    assertTrue(late.isEnabled());
                } finally { controller.close(); }
            });
        }
    }

    @Test
    public void lockedRadarBlocksControlsAndShortTapsThenUnlocksAfterDeliberateHold() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            int[][] positions = new int[3][];
            int[] orientation = new int[1];
            scenario.onActivity(activity -> {
                View lock = activity.findViewById(id(activity, "btn_lock_touch"));
                assertNotNull("Radar touch-lock button must exist", lock);
                positions[0] = center(activity.findViewById(R.id.btn_manual_rotate));
                positions[1] = center(activity.findViewById(R.id.nav_dashboard));
                orientation[0] = activity.getRequestedOrientation();
                lock.performClick();
                View overlay = activity.findViewById(id(activity, "radar_touch_lock_overlay"));
                assertEquals(View.VISIBLE, overlay.getVisibility());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            // Unlock from the radar canvas, not just the old dedicated button.
            scenario.onActivity(activity -> {
                View radar = activity.findViewById(R.id.radar_view);
                positions[2] = center(radar);
                positions[2][1] -= radar.getHeight() / 4;
            });
            tap(positions[0]);
            tap(positions[1]);
            tap(positions[2]);
            scenario.onActivity(activity -> {
                assertEquals(orientation[0], activity.getRequestedOrientation());
                assertEquals(R.id.nav_radar, ((BottomNavigationView) activity.findViewById(
                        R.id.bottom_nav)).getSelectedItemId());
                assertEquals(View.VISIBLE, activity.findViewById(id(activity,
                        "radar_touch_lock_overlay")).getVisibility());
                MqttService.getInstance().dispatchTargetsToListeners(JsonParser.parseString(
                        "{\"fc\":12345,\"ec\":0,\"t\":[{\"p\":false},{\"p\":false},{\"p\":false}]}")
                        .getAsJsonObject());
                assertTrue("Radar frames must continue updating behind the guard",
                        ((TextView) activity.findViewById(R.id.tv_radar_stats))
                                .getText().toString().contains("12345"));
                activity.getOnBackPressedDispatcher().onBackPressed();
            });

            long time = SystemClock.uptimeMillis();
            pointer(MotionEvent.ACTION_DOWN, positions[2], time);
            SystemClock.sleep(1000);
            pointer(MotionEvent.ACTION_UP, positions[2], time);
            SystemClock.sleep(1200);
            scenario.onActivity(activity -> assertEquals("An interrupted hold must not unlock",
                    View.VISIBLE, activity.findViewById(id(activity,
                            "radar_touch_lock_overlay")).getVisibility()));

            time = SystemClock.uptimeMillis();
            pointer(MotionEvent.ACTION_DOWN, positions[2], time);
            SystemClock.sleep(2200);
            pointer(MotionEvent.ACTION_UP, positions[2], time);
            scenario.onActivity(activity -> assertEquals(View.GONE,
                    activity.findViewById(id(activity, "radar_touch_lock_overlay")).getVisibility()));
            tap(positions[1]);
            scenario.onActivity(activity -> assertEquals(R.id.nav_dashboard,
                    ((BottomNavigationView) activity.findViewById(R.id.bottom_nav)).getSelectedItemId()));
        }
    }

    @Test
    public void holdingOverMenuUnlocksWithoutClickingThroughOnRelease() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            int[][] menu = new int[1][];
            scenario.onActivity(activity -> {
                menu[0] = center(activity.findViewById(R.id.nav_dashboard));
                activity.findViewById(R.id.btn_lock_touch).performClick();
                // Even if a child overlay is accidentally hidden/reordered, Activity must guard input.
                activity.findViewById(R.id.radar_touch_lock_overlay).setVisibility(View.GONE);
            });
            tap(menu[0]);
            scenario.onActivity(activity -> assertEquals(R.id.nav_radar,
                    ((BottomNavigationView) activity.findViewById(R.id.bottom_nav)).getSelectedItemId()));
            long time = SystemClock.uptimeMillis();
            pointer(MotionEvent.ACTION_DOWN, menu[0], time);
            SystemClock.sleep(2200);
            pointer(MotionEvent.ACTION_UP, menu[0], time);
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.radar_touch_lock_overlay).getVisibility());
                assertEquals("The release that unlocks must never activate the menu", R.id.nav_radar,
                        ((BottomNavigationView) activity.findViewById(R.id.bottom_nav)).getSelectedItemId());
            });
            tap(menu[0]);
            scenario.onActivity(activity -> assertEquals(R.id.nav_dashboard,
                    ((BottomNavigationView) activity.findViewById(R.id.bottom_nav)).getSelectedItemId()));
        }
    }

    @Test
    public void movingOrAddingAFingerCancelsUnlockAndKeysCannotActivateControls() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            int[][] point = new int[1][];
            scenario.onActivity(activity -> {
                point[0] = center(activity.findViewById(R.id.radar_view));
                activity.findViewById(R.id.btn_lock_touch).performClick();
                View rotate = activity.findViewById(R.id.btn_manual_rotate);
                rotate.setFocusableInTouchMode(true);
                assertTrue(rotate.requestFocus());
                int orientation = activity.getRequestedOrientation();
                assertTrue(activity.dispatchKeyEvent(new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)));
                assertTrue(activity.dispatchKeyEvent(new android.view.KeyEvent(
                        android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER)));
                assertEquals("Enter must not activate a focused underlying control",
                        orientation, activity.getRequestedOrientation());
            });
            long time = SystemClock.uptimeMillis();
            pointer(MotionEvent.ACTION_DOWN, point[0], time);
            pointer(MotionEvent.ACTION_MOVE, new int[]{point[0][0] + 100, point[0][1]}, time);
            SystemClock.sleep(2200);
            pointer(MotionEvent.ACTION_UP, point[0], time);
            scenario.onActivity(activity -> assertEquals(View.VISIBLE,
                    activity.findViewById(R.id.radar_touch_lock_overlay).getVisibility()));

            time = SystemClock.uptimeMillis();
            pointer(MotionEvent.ACTION_DOWN, point[0], time);
            final long down = time;
            scenario.onActivity(activity -> {
                MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[2];
                MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
                for (int i = 0; i < 2; i++) {
                    props[i] = new MotionEvent.PointerProperties();
                    props[i].id = i;
                    props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                    coords[i] = new MotionEvent.PointerCoords();
                    coords[i].x = point[0][0] + i * 50;
                    coords[i].y = point[0][1];
                }
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                        2, props, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
                try { assertTrue(activity.dispatchTouchEvent(event)); }
                finally { event.recycle(); }
            });
            SystemClock.sleep(2200);
            pointer(MotionEvent.ACTION_UP, point[0], time);
            scenario.onActivity(activity -> assertEquals(View.VISIBLE,
                    activity.findViewById(R.id.radar_touch_lock_overlay).getVisibility()));
        }
    }

    @Test
    public void backgroundingCancelsAnIncompleteUnlockHold() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            int[][] button = new int[1][];
            scenario.onActivity(activity ->
                    activity.findViewById(id(activity, "btn_lock_touch")).performClick());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity ->
                    button[0] = center(activity.findViewById(id(activity, "btn_unlock_touch"))));
            long time = SystemClock.uptimeMillis();
            pointer(MotionEvent.ACTION_DOWN, button[0], time);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED);
            SystemClock.sleep(2200);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            pointer(MotionEvent.ACTION_UP, button[0], time);
            scenario.onActivity(activity -> {
                assertEquals("Leaving the app must cancel a pending unlock", View.VISIBLE,
                        activity.findViewById(id(activity, "radar_touch_lock_overlay")).getVisibility());
                activity.findViewById(id(activity, "btn_unlock_touch")).performLongClick();
            });
        }
    }

    @Test
    public void guardSurvivesActivityRecreationAndSupportsAccessibleUnlock() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                View lock = activity.findViewById(id(activity, "btn_lock_touch"));
                assertNotNull("Radar touch-lock button must exist", lock);
                lock.performClick();
                UpdateInfo update = new UpdateInfo();
                update.versionCode = 9999;
                update.versionName = "touch-lock-test";
                update.apkUrl = "https://example.invalid/test.apk";
                update.sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
                activity.offerUpdate(update);
                activity.getSupportFragmentManager().executePendingTransactions();
                assertNull("Updates must not appear on top of the touch guard",
                        activity.getSupportFragmentManager().findFragmentByTag("UpdateDialog"));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(id(activity,
                        "radar_touch_lock_overlay")).getVisibility());
                assertTrue(activity.findViewById(id(activity, "btn_unlock_touch"))
                        .performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo
                                .ACTION_LONG_CLICK, null));
                assertEquals(View.GONE, activity.findViewById(id(activity,
                        "radar_touch_lock_overlay")).getVisibility());
                activity.getSupportFragmentManager().executePendingTransactions();
                assertNotNull("Deferred update must survive recreation and appear after unlocking",
                        activity.getSupportFragmentManager().findFragmentByTag("UpdateDialog"));
                ((androidx.fragment.app.DialogFragment) activity.getSupportFragmentManager()
                        .findFragmentByTag("UpdateDialog")).dismissNow();
            });
        }
    }
}
