package com.example.radarhumanapplication;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.TextView;
import java.util.function.Consumer;

/** An in-app touch guard. Radar rendering and MQTT continue behind the overlay. */
final class RadarTouchLockController implements AutoCloseable {
    static final long UNLOCK_HOLD_MS = 2000;
    private final View content;
    private final View overlay;
    private final TextView unlockButton;
    private final Consumer<Boolean> stateChanged;
    private final int touchSlop;
    private final Runnable finishHold;
    private boolean locked;
    private int previousAccessibility;

    RadarTouchLockController(View content, View overlay, TextView unlockButton,
                             Consumer<Boolean> stateChanged) {
        this.content = content;
        this.overlay = overlay;
        this.unlockButton = unlockButton;
        this.stateChanged = stateChanged;
        touchSlop = ViewConfiguration.get(content.getContext()).getScaledTouchSlop();
        finishHold = () -> {
            if (!locked || !unlockButton.isPressed()) return;
            unlockButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setLocked(false);
        };
        unlockButton.setOnTouchListener(this::onUnlockTouch);
        unlockButton.setOnClickListener(view -> {
            if (locked) announceUnlockHint();
        });
        // TalkBack's deliberate long-click action is the accessible equivalent of holding.
        unlockButton.setOnLongClickListener(view -> {
            setLocked(false);
            return true;
        });
    }

    boolean isLocked() {
        return locked;
    }

    void setLocked(boolean next) {
        if (locked == next) return;
        cancelPendingHold();
        locked = next;
        if (next) {
            previousAccessibility = content.getImportantForAccessibility();
            content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            overlay.setVisibility(View.VISIBLE);
            overlay.bringToFront();
            unlockButton.requestFocus();
            announceUnlockHint();
        } else {
            overlay.setVisibility(View.GONE);
            content.setImportantForAccessibility(previousAccessibility);
        }
        stateChanged.accept(next);
    }

    void announceUnlockHint() {
        unlockButton.announceForAccessibility(
                unlockButton.getContext().getString(R.string.radar_touch_unlock_hint));
    }

    void cancelPendingHold() {
        unlockButton.removeCallbacks(finishHold);
        unlockButton.setPressed(false);
        unlockButton.setText(R.string.radar_touch_unlock_button);
    }

    private boolean onUnlockTouch(View view, MotionEvent event) {
        if (!locked) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelPendingHold();
                unlockButton.setPressed(true);
                unlockButton.setText(R.string.radar_touch_unlock_holding);
                unlockButton.postDelayed(finishHold, UNLOCK_HOLD_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getX() < -touchSlop || event.getY() < -touchSlop
                        || event.getX() > view.getWidth() + touchSlop
                        || event.getY() > view.getHeight() + touchSlop) {
                    cancelPendingHold();
                }
                break;
            case MotionEvent.ACTION_UP:
                cancelPendingHold();
                view.performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelPendingHold();
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public void close() {
        cancelPendingHold();
    }
}
