package com.example.radarhumanapplication;

import android.os.SystemClock;
import android.view.View;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Verifies actual window insets and viewport bounds instead of only checking a UI flag. */
@RunWith(AndroidJUnit4.class)
public class RadarFullscreenInstrumentedTest {
    private boolean viewportFillsWindow(MainActivity activity) {
        View root = activity.findViewById(R.id.main);
        View radar = activity.findViewById(R.id.radar_view);
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
        return insets != null
                && !insets.isVisible(WindowInsetsCompat.Type.statusBars())
                && !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                && root.getPaddingLeft() == 0 && root.getPaddingTop() == 0
                && root.getPaddingRight() == 0 && root.getPaddingBottom() == 0
                && radar.getWidth() == root.getWidth() && radar.getHeight() == root.getHeight();
    }

    private void awaitFullscreen(ActivityScenario<MainActivity> scenario) {
        boolean[] full = new boolean[1];
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> full[0] = viewportFillsWindow(activity));
            if (full[0]) return;
            SystemClock.sleep(50);
        }
        fail("Fullscreen must hide system bars, remove insets and fill the entire window");
    }

    @Test
    public void fullscreenFillsWindowThenRestoresNormalChrome() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.radar_view).performClick());
            awaitFullscreen(scenario);
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.bottom_nav).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.radar_status_bar).getVisibility());
                int exit = activity.getResources().getIdentifier("btn_exit_fullscreen",
                        "id", activity.getPackageName());
                assertNotEquals("Full screen needs a visible exit control", 0, exit);
                assertEquals(View.VISIBLE, activity.findViewById(exit).getVisibility());
                activity.findViewById(exit).performClick();
            });
            long deadline = SystemClock.uptimeMillis() + 5000;
            boolean[] restored = new boolean[1];
            while (SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity(activity -> {
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.findViewById(R.id.main));
                    restored[0] = insets != null && insets.isVisible(WindowInsetsCompat.Type.statusBars())
                            && activity.findViewById(R.id.bottom_nav).getVisibility() == View.VISIBLE
                            && activity.findViewById(R.id.radar_status_bar).getVisibility() == View.VISIBLE
                            && activity.findViewById(R.id.main).getPaddingTop() > 0;
                });
                if (restored[0]) break;
                SystemClock.sleep(50);
            }
            assertTrue("Exiting fullscreen must restore normal system bars and layout", restored[0]);
        }
    }

    @Test
    public void fullscreenAndTouchLockSurviveRecreationAndBackExitsOnlyAfterUnlock() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.radar_view).performClick());
            awaitFullscreen(scenario);
            scenario.onActivity(activity -> activity.findViewById(R.id.btn_lock_touch).performClick());
            scenario.recreate();
            awaitFullscreen(scenario);
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.radar_touch_lock_overlay).getVisibility());
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.radar_touch_lock_overlay).getVisibility());
                activity.findViewById(R.id.btn_unlock_touch).performLongClick();
            });
            awaitFullscreen(scenario);
            scenario.onActivity(activity -> {
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.bottom_nav).getVisibility());
            });
        }
    }

    @Test
    public void radarHonorsExactParentWidthEvenInAWideWindow() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                RadarView radar = new RadarView(activity, null);
                radar.measure(View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
                assertEquals("Radar viewport must not shrink the width requested by its parent",
                        1400, radar.getMeasuredWidth());
                assertEquals(300, radar.getMeasuredHeight());
            });
        }
    }
}
