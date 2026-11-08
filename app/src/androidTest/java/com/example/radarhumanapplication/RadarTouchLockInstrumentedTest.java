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
            scenario.onActivity(activity -> positions[2] = center(
                    activity.findViewById(id(activity, "btn_unlock_touch"))));
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
