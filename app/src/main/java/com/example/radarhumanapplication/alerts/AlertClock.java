package com.example.radarhumanapplication.alerts;

/**
 * Pluggable clock so cooldown logic in {@link AlertManager} can be unit-tested without
 * relying on real wall time.
 */
public interface AlertClock {

    /** Returns "now" as a millisecond timestamp. */
    long nowMillis();

    /** Real system clock — used in production. */
    AlertClock SYSTEM = System::currentTimeMillis;
}
