package com.example.radarhumanapplication.eventlog;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure detection-state machine for one target. Pulled out of {@link EventLogger} so the
 * threshold-crossing logic is unit-testable without Android.
 *
 * <p>Emits:
 * <ul>
 *   <li>{@link Event.Type#ENTER} when presence flips false → true</li>
 *   <li>{@link Event.Type#LEAVE} when presence flips true → false</li>
 *   <li>{@link Event.Type#MOVE} when the present target's distance crosses a 1 m
 *       (1000 mm) increment compared with the previous frame</li>
 * </ul>
 */
public class EventDetector {

    public static final int BUCKET_MM = 1000;

    private final int targetIndex;
    private boolean lastPresent;
    private int lastBucket;
    private boolean hasLast;

    public EventDetector(int targetIndex) {
        this.targetIndex = targetIndex;
        this.lastPresent = false;
        this.lastBucket = 0;
        this.hasLast = false;
    }

    public List<Event> observe(long timestampMs, boolean present, int distanceMm) {
        List<Event> out = new ArrayList<>(2);
        if (present && !lastPresent) {
            out.add(new Event(timestampMs, Event.Type.ENTER, targetIndex, distanceMm,
                    "T" + (targetIndex + 1) + " entered at " + distanceMm + "mm"));
            lastBucket = bucket(distanceMm);
            hasLast = true;
        } else if (!present && lastPresent) {
            out.add(new Event(timestampMs, Event.Type.LEAVE, targetIndex,
                    hasLast ? lastBucket * BUCKET_MM : 0,
                    "T" + (targetIndex + 1) + " left"));
            hasLast = false;
        } else if (present) {
            int b = bucket(distanceMm);
            if (!hasLast) {
                lastBucket = b;
                hasLast = true;
            } else if (b != lastBucket) {
                out.add(new Event(timestampMs, Event.Type.MOVE, targetIndex, distanceMm,
                        "T" + (targetIndex + 1) + " crossed "
                                + (b > lastBucket ? "out to " : "in to ")
                                + (b * BUCKET_MM) + "mm bucket"));
                lastBucket = b;
            }
        }
        lastPresent = present;
        return out;
    }

    private static int bucket(int distanceMm) {
        if (distanceMm < 0) distanceMm = 0;
        return distanceMm / BUCKET_MM;
    }

    public int targetIndex() { return targetIndex; }

    public boolean lastPresent() { return lastPresent; }
}
