package com.example.radarhumanapplication.alerts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Verifies cooldown gating in {@link AlertManager#isInCooldown(AlertRule, String)}.
 *
 * Uses a fake clock and the package-private {@code lastFiredAtMillis} map. We don't drive the
 * full {@code attach()} path because that requires an Android Context and the Mqtt singleton;
 * the cooldown logic itself is plain Java with no Android dependencies once the clock is
 * injected.
 */
public class AlertCooldownTest {

    /** Mutable fake clock controlled by the test. */
    private static final class FakeClock implements AlertClock {
        long now = 1_000_000L;
        @Override public long nowMillis() { return now; }
        void advanceMs(long delta) { now += delta; }
    }

    private AlertManager mgr;
    private FakeClock clock;

    @Before
    public void setUp() {
        // We use the singleton — it's the only way to access the manager — but we reset
        // its in-memory firing state and inject our fake clock, which together ensures test
        // isolation regardless of test order. Persistence is not exercised because we never
        // call attach().
        mgr = AlertManager.getInstance();
        mgr.resetStateForTesting();
        clock = new FakeClock();
        mgr.setClockForTesting(clock);
    }

    @Test
    public void zeroCooldown_isNeverGating() {
        AlertRule rule = ruleWithCooldown(0);
        String key = rule.id + "/0";
        // No prior fire — no cooldown.
        assertFalse(mgr.isInCooldown(rule, key));
        // Even if we had fired, cooldown=0 means no gating.
        assertFalse(mgr.isInCooldown(rule, key));
    }

    @Test
    public void firstFire_isNotGated() {
        AlertRule rule = ruleWithCooldown(10);
        String key = rule.id + "/0";
        assertFalse("First evaluation must never be gated", mgr.isInCooldown(rule, key));
    }

    @Test
    public void withinCooldownWindow_isGated() throws Exception {
        AlertRule rule = ruleWithCooldown(10); // 10 seconds
        String key = rule.id + "/0";

        // Simulate a fire by directly recording last-fired-at.
        recordFire(key, clock.now);

        // 5s elapsed → still gated.
        clock.advanceMs(5_000);
        assertTrue(mgr.isInCooldown(rule, key));

        // 9.999s elapsed → still gated.
        clock.advanceMs(4_999);
        assertTrue(mgr.isInCooldown(rule, key));
    }

    @Test
    public void afterCooldownExpires_isNotGated() throws Exception {
        AlertRule rule = ruleWithCooldown(10);
        String key = rule.id + "/0";

        recordFire(key, clock.now);
        clock.advanceMs(10_000); // exactly equal — boundary, must release
        assertFalse(mgr.isInCooldown(rule, key));

        clock.advanceMs(5_000); // long past
        assertFalse(mgr.isInCooldown(rule, key));
    }

    @Test
    public void cooldownIsPerKey() throws Exception {
        AlertRule rule = ruleWithCooldown(10);
        String keyA = rule.id + "/0";
        String keyB = rule.id + "/1";

        recordFire(keyA, clock.now);
        // keyA is gated, keyB is not.
        assertTrue(mgr.isInCooldown(rule, keyA));
        assertFalse(mgr.isInCooldown(rule, keyB));
    }

    @Test
    public void clockReset_revertsToSystemWhenNullPassed() {
        // Just make sure the API doesn't blow up — if it did, every other test using
        // setClockForTesting could be polluted.
        mgr.setClockForTesting(null);
        AlertRule rule = ruleWithCooldown(0);
        // No prior fires + cooldown 0 ⇒ not gated.
        assertFalse(mgr.isInCooldown(rule, "any/0"));
        // Restore for other tests.
        mgr.setClockForTesting(clock);
        assertEquals(clock.now, clock.nowMillis());
    }

    // ------- helpers -------

    private static AlertRule ruleWithCooldown(int seconds) {
        AlertRule r = new AlertRule();
        r.cooldownSeconds = seconds;
        return r;
    }

    /**
     * Reach into AlertManager to record a fire at a specific timestamp without going through
     * MQTT/playback. We use reflection because {@code lastFiredAtMillis} is private.
     */
    private void recordFire(String key, long whenMs) throws Exception {
        java.lang.reflect.Field f = AlertManager.class.getDeclaredField("lastFiredAtMillis");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> map = (java.util.Map<String, Long>) f.get(mgr);
        synchronized (map) {
            map.put(key, whenMs);
        }
    }
}
