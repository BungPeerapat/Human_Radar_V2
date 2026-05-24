package com.example.radarhumanapplication.alerts;

import com.google.gson.JsonObject;

/**
 * Mirror of the ESP32 firmware's {@code AlertPattern::Config} so the Android
 * companion can play the exact same buzzer pattern locally.
 *
 * <p>Fields use the short keys from the {@code /api/alert} REST contract.</p>
 */
public final class AlertPatternConfig {
    public boolean alertEnabled   = true;   // master on/off (matches "ae")
    public boolean ledWifiEnabled = true;   // ESP32-only — mobile ignores (matches "le")
    public int     beepShortMs    = 200;    // "bs"
    public int     beepLongMs     = 800;    // "bl"
    public int     beepGapMs      = 200;    // "bg"
    public int     debounceMs     = 500;    // "db"
    public int     maxRangeMm     = 6000;   // "mr"  (0 = no limit)

    /** Mobile-only knobs (not persisted on the device). */
    public SoundType soundType = SoundType.TONE;
    public int       volumePct = 100;       // 0..100
    /** How the alert audio is routed when headphones / Bluetooth are
     *  connected. Default NOTIFICATION → routes only to the connected
     *  output device, same as a normal chat notification. ALARM →
     *  Android plays through speaker AND any connected output at the
     *  same time so it can't be muted by plugging in headphones. */
    public Routing routing = Routing.NOTIFICATION;

    /** content:// URIs for the user-picked short/long clips (WAV mode). */
    public String shortSoundUri = "";
    public String longSoundUri  = "";

    public enum SoundType {
        TONE,    // ToneGenerator beep
        WAV,     // bundled res/raw beep_short / beep_long
        TTS      // spoken count ("1 target", "2 targets")
    }

    public enum Routing {
        /** USAGE_NOTIFICATION / STREAM_NOTIFICATION — respects headphone
         *  routing. Plug in headphones, sound only goes to headphones. */
        NOTIFICATION,
        /** USAGE_ALARM / STREAM_ALARM — Android forces playback through
         *  both the speaker and any connected output. Use when the alert
         *  is safety-critical and must not be silenced by accident. */
        ALARM
    }

    public AlertPatternConfig copy() {
        AlertPatternConfig c = new AlertPatternConfig();
        c.alertEnabled   = alertEnabled;
        c.ledWifiEnabled = ledWifiEnabled;
        c.beepShortMs    = beepShortMs;
        c.beepLongMs     = beepLongMs;
        c.beepGapMs      = beepGapMs;
        c.debounceMs     = debounceMs;
        c.maxRangeMm     = maxRangeMm;
        c.soundType      = soundType;
        c.volumePct      = volumePct;
        c.routing        = routing;
        c.shortSoundUri  = shortSoundUri;
        c.longSoundUri   = longSoundUri;
        return c;
    }

    public JsonObject toEspJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ae", alertEnabled   ? 1 : 0);
        o.addProperty("le", ledWifiEnabled ? 1 : 0);
        o.addProperty("bs", beepShortMs);
        o.addProperty("bl", beepLongMs);
        o.addProperty("bg", beepGapMs);
        o.addProperty("db", debounceMs);
        o.addProperty("mr", maxRangeMm);
        return o;
    }

    public static AlertPatternConfig fromEspJson(JsonObject o) {
        AlertPatternConfig c = new AlertPatternConfig();
        if (o == null) return c;
        if (o.has("ae")) c.alertEnabled   = o.get("ae").getAsInt() != 0;
        if (o.has("le")) c.ledWifiEnabled = o.get("le").getAsInt() != 0;
        if (o.has("bs")) c.beepShortMs    = o.get("bs").getAsInt();
        if (o.has("bl")) c.beepLongMs     = o.get("bl").getAsInt();
        if (o.has("bg")) c.beepGapMs      = o.get("bg").getAsInt();
        if (o.has("db")) c.debounceMs     = o.get("db").getAsInt();
        if (o.has("mr")) c.maxRangeMm     = o.get("mr").getAsInt();
        return c;
    }
}
