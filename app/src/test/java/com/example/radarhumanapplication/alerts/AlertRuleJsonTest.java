package com.example.radarhumanapplication.alerts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.Test;

/** Verifies that all new {@link AlertRule} fields roundtrip cleanly through Gson. */
public class AlertRuleJsonTest {

    private final Gson gson = new Gson();

    @Test
    public void roundTrip_preservesAllFields() {
        AlertRule original = new AlertRule();
        original.id = "fixed-id";
        original.enabled = false;
        original.name = "Living room";
        original.operator = AlertOperator.GE;
        original.distanceMm = 2500;
        original.targetIndex = 1;
        original.soundUri = "content://media/x";
        original.soundLabel = "Bell";
        original.audioStream = android.media.AudioManager.STREAM_NOTIFICATION;
        original.loop = true;
        original.vibrate = true;
        original.vibrationPattern = AlertRule.VIB_SOS;
        original.speak = true;
        original.spokenText = "Heads up: {target} at {distance}";
        original.ttsLanguageTag = "en-GB";
        original.cooldownSeconds = 15;

        String json = gson.toJson(original);
        assertNotNull(json);
        assertTrue("JSON must contain new fields", json.contains("vibrate"));
        assertTrue(json.contains("vibrationPattern"));
        assertTrue(json.contains("speak"));
        assertTrue(json.contains("spokenText"));
        assertTrue(json.contains("ttsLanguageTag"));
        assertTrue(json.contains("cooldownSeconds"));

        AlertRule restored = gson.fromJson(json, AlertRule.class);
        assertEquals(original.id, restored.id);
        assertEquals(original.enabled, restored.enabled);
        assertEquals(original.name, restored.name);
        assertEquals(original.operator, restored.operator);
        assertEquals(original.distanceMm, restored.distanceMm);
        assertEquals(original.targetIndex, restored.targetIndex);
        assertEquals(original.soundUri, restored.soundUri);
        assertEquals(original.soundLabel, restored.soundLabel);
        assertEquals(original.audioStream, restored.audioStream);
        assertEquals(original.loop, restored.loop);
        assertEquals(original.vibrate, restored.vibrate);
        assertEquals(original.vibrationPattern, restored.vibrationPattern);
        assertEquals(original.speak, restored.speak);
        assertEquals(original.spokenText, restored.spokenText);
        assertEquals(original.ttsLanguageTag, restored.ttsLanguageTag);
        assertEquals(original.cooldownSeconds, restored.cooldownSeconds);
    }

    @Test
    public void deserialize_legacyJson_appliesDefaults() {
        // Legacy JSON without new fields. New fields should fall back to their declared defaults.
        String legacy = "{"
                + "\"id\":\"legacy\","
                + "\"enabled\":true,"
                + "\"name\":\"Old\","
                + "\"operator\":\"LE\","
                + "\"distanceMm\":1500,"
                + "\"targetIndex\":-1,"
                + "\"audioStream\":4,"
                + "\"loop\":false,"
                + "\"soundLabel\":\"(none)\""
                + "}";
        AlertRule r = gson.fromJson(legacy, AlertRule.class);
        assertEquals("legacy", r.id);
        assertEquals(1500, r.distanceMm);
        // New fields should keep their defaults.
        assertEquals(false, r.vibrate);
        assertEquals(AlertRule.VIB_SHORT, r.vibrationPattern);
        assertEquals(false, r.speak);
        assertEquals(AlertRule.DEFAULT_SPOKEN_TEXT, r.spokenText);
        assertEquals("en-US", r.ttsLanguageTag);
        assertEquals(0, r.cooldownSeconds);
    }

    @Test
    public void renderTemplate_substitutesPlaceholders() {
        AlertRule rule = new AlertRule();
        rule.operator = AlertOperator.LE;
        String out = AlertTtsPlayer.renderTemplate(
                "Target {target} {operator} {distance}", rule, 1, 1234);
        // 1 -> "2" (1-indexed display), 1234mm -> "1.2"
        assertEquals("Target 2 <= 1.2", out);
    }

    @Test
    public void renderTemplate_anyTargetRendersAsAny() {
        AlertRule rule = new AlertRule();
        String out = AlertTtsPlayer.renderTemplate(
                "{target} at {distance}", rule, AlertRule.TARGET_ANY, 800);
        assertEquals("Any at 0.8", out);
    }
}
