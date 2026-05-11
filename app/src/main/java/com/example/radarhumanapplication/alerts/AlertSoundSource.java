package com.example.radarhumanapplication.alerts;

/**
 * Abstraction over the three "how do we make a noise" backends used by
 * {@link AlertPatternPlayer}: tone generator, bundled WAV clips, or TTS.
 *
 * <p>The player invokes {@link #onPatternStart} once when a new beep
 * pattern begins, then schedules {@link #playShort}/{@link #playLong}
 * for each beep edge. TTS implementations typically handle everything in
 * {@code onPatternStart} and treat the beep calls as no-ops.</p>
 */
public interface AlertSoundSource {
    /** Called once when a new pattern begins. {@code totalShorts} excludes the long prefix. */
    void onPatternStart(boolean longPrefix, int totalShorts, int currentTargetCount);

    /** Play a single short beep for the given duration. */
    void playShort(int durationMs);

    /** Play a single long beep for the given duration. */
    void playLong(int durationMs);

    /** Free underlying resources (called on player shutdown or backend switch). */
    void release();
}
