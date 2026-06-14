package com.example.radarhumanapplication.alerts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for the "stuck alert" bug: a looping alert must stop when the target leaves
 * the scene ({@code present == false}) or the device goes offline / the radar stream stalls.
 *
 * <p>The fire/stop decision logic is plain Java once a fake {@link AlertSoundPlayer} is injected,
 * so these run as pure JVM unit tests with no Android Context — mirroring {@link AlertCooldownTest}.
 * The sustained-alert sound is the only thing that can hang (vibration uses a non-repeating
 * waveform and TTS is one-shot), so the player is the only side-effect we assert on.</p>
 */
public class AlertSilenceTest {

    /** Records stop()/stopAll() calls instead of touching a real MediaPlayer. */
    private static final class FakePlayer extends AlertSoundPlayer {
        final List<String> stopped = new ArrayList<>();
        int stopAllCount = 0;

        FakePlayer() { super(); }

        @Override public void play(AlertRule rule) { /* no-op */ }
        @Override public void stop(String ruleId) { stopped.add(ruleId); }
        @Override public void stopAll() { stopAllCount++; }
    }

    private AlertManager mgr;
    private FakePlayer player;

    @Before
    public void setUp() {
        mgr = AlertManager.getInstance();
        mgr.resetStateForTesting();
        mgr.setAttachedForTesting(false);   // singleton is shared across test classes — reset it
        player = new FakePlayer();
        mgr.setPlayerForTesting(player);
    }

    @Test
    public void targetGone_stopsLoopingSound() {
        AlertRule rule = loopingRule();
        String key = rule.id + "/0";
        mgr.markFiredForTesting(key);

        // Target disappeared (present=false) → must clear the flag AND stop the loop.
        mgr.clearFireAndStopLoop(rule, key);

        assertFalse("fired flag must be cleared when target leaves", mgr.isFiredForTesting(key));
        assertEquals("looping sound must be stopped once", 1, player.stopped.size());
        assertEquals(rule.id, player.stopped.get(0));
    }

    @Test
    public void targetGone_nonLooping_clearsFlagWithoutStopping() {
        AlertRule rule = new AlertRule();   // loop defaults to false
        String key = rule.id + "/1";
        mgr.markFiredForTesting(key);

        mgr.clearFireAndStopLoop(rule, key);

        assertFalse(mgr.isFiredForTesting(key));
        assertTrue("non-looping rule has nothing to stop", player.stopped.isEmpty());
    }

    @Test
    public void clearFireAndStopLoop_whenNotCurrentlyFiring_isNoOp() {
        AlertRule rule = loopingRule();
        // Never marked as fired → no stop, no exception.
        mgr.clearFireAndStopLoop(rule, rule.id + "/0");

        assertTrue(player.stopped.isEmpty());
    }

    @Test
    public void silenceAll_stopsEverythingAndClearsFiredState() {
        AlertRule a = loopingRule();
        AlertRule b = loopingRule();
        mgr.markFiredForTesting(a.id + "/0");
        mgr.markFiredForTesting(b.id + "/2");

        // Device offline / stream stalled → silence everything at once.
        mgr.silenceAll();

        assertEquals("all sounds stopped in a single call", 1, player.stopAllCount);
        assertFalse(mgr.isFiredForTesting(a.id + "/0"));
        assertFalse(mgr.isFiredForTesting(b.id + "/2"));
    }

    @Test
    public void deviceOffline_silencesAll() {
        mgr.setAttachedForTesting(true);
        mgr.markFiredForTesting(loopingRule().id + "/0");

        mgr.onDeviceStatus("offline");

        assertEquals("offline status must stop all sounds", 1, player.stopAllCount);
    }

    @Test
    public void deviceUnknownStatus_silencesAll() {
        mgr.setAttachedForTesting(true);

        mgr.onDeviceStatus("unknown");   // e.g. device switch / MQTT drop

        assertEquals(1, player.stopAllCount);
    }

    @Test
    public void deviceOnline_doesNotSilence() {
        mgr.setAttachedForTesting(true);

        mgr.onDeviceStatus("online");

        assertEquals("coming online must not stop a legitimately firing alert",
                0, player.stopAllCount);
    }

    @Test
    public void statusBeforeAttach_isIgnored() {
        // attached defaults to false in setUp — callback must be inert.
        mgr.onDeviceStatus("offline");

        assertEquals(0, player.stopAllCount);
    }

    // ------- helpers -------

    private static AlertRule loopingRule() {
        AlertRule r = new AlertRule();
        r.loop = true;
        r.soundUri = "content://fake/ringtone";
        return r;
    }
}
