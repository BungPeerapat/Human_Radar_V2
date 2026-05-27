package com.example.radarhumanapplication.sensor;

/**
 * Plain-data holder for the five sensor-behavior knobs that the firmware
 * exposes via {@code /api/config}:
 *
 * <ul>
 *   <li>{@code pi} — publish interval (ms, 50..2000)</li>
 *   <li>{@code ud} — unmanned delay (ms, 1000..60000)</li>
 *   <li>{@code tt} — target timeout (ms, 100..10000)</li>
 *   <li>{@code sn} — sensitivity (0..9)</li>
 *   <li>{@code mt} — multi-target mode (0 = single, 1 = multi up to 3)</li>
 * </ul>
 *
 * Mirrors the simple POJO style of {@code AlertPatternConfig} — fields are
 * public so the fragment can read / write them directly.
 */
public class SensorConfig {

    /** Defaults match firmware {@code config_manager.cpp} factory values. */
    public int publishIntervalMs = 100;
    public int unmannedDelayMs   = 5000;
    public int targetTimeoutMs   = 1000;
    public int sensitivity       = 5;
    public boolean multiTarget   = true;

    /** Returns a deep copy — useful when the UI needs a working snapshot
     *  it can mutate without touching the persisted instance. */
    public SensorConfig copy() {
        SensorConfig c = new SensorConfig();
        c.publishIntervalMs = this.publishIntervalMs;
        c.unmannedDelayMs   = this.unmannedDelayMs;
        c.targetTimeoutMs   = this.targetTimeoutMs;
        c.sensitivity       = this.sensitivity;
        c.multiTarget       = this.multiTarget;
        return c;
    }
}
