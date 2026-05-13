package com.example.radarhumanapplication.alerts;

import android.media.AudioManager;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * One distance-trigger rule. Persisted as JSON via Gson, so prefer simple types and keep field
 * names stable.
 */
public class AlertRule {

    /** Apply rule to any of the three radar targets. */
    public static final int TARGET_ANY = -1;

    /** Default spoken text template. Supports placeholders {target}, {distance}, {operator}. */
    public static final String DEFAULT_SPOKEN_TEXT = "Target {target} at {distance} meters";

    /** Vibration patterns. Stored as string for stable serialization. */
    public static final String VIB_SHORT = "Short";
    public static final String VIB_MEDIUM = "Medium";
    public static final String VIB_LONG = "Long";
    public static final String VIB_SOS = "SOS";

    @SerializedName("id")
    public String id = UUID.randomUUID().toString();

    @SerializedName("enabled")
    public boolean enabled = true;

    @SerializedName("name")
    public String name = "";

    /** {@link AlertOperator}. Stored as ordinal for forward compatibility. */
    @SerializedName("operator")
    public AlertOperator operator = AlertOperator.LE;

    /** Threshold in millimetres. */
    @SerializedName("distanceMm")
    public int distanceMm = 1000;

    /** Which radar slot this rule watches. {@link #TARGET_ANY} or 0..2. */
    @SerializedName("targetIndex")
    public int targetIndex = TARGET_ANY;

    /** {@code content://} URI of the chosen ringtone, or null = silent rule. */
    @SerializedName("soundUri")
    public String soundUri;

    /** Display name from RingtoneManager, shown in the list. */
    @SerializedName("soundLabel")
    public String soundLabel = "(none)";

    /** {@link AudioManager} stream type used for playback volume. */
    @SerializedName("audioStream")
    public int audioStream = AudioManager.STREAM_ALARM;

    /** Loop the sound until the rule no longer matches. */
    @SerializedName("loop")
    public boolean loop = false;

    // ---- New (Batch B) fields ----

    /** Vibrate when this rule fires. */
    @SerializedName("vibrate")
    public boolean vibrate = false;

    /** One of {@link #VIB_SHORT}, {@link #VIB_MEDIUM}, {@link #VIB_LONG}, {@link #VIB_SOS}. */
    @SerializedName("vibrationPattern")
    public String vibrationPattern = VIB_SHORT;

    /** Speak the rule via TTS when it fires. */
    @SerializedName("speak")
    public boolean speak = false;

    /** Spoken text template. See {@link #DEFAULT_SPOKEN_TEXT}. */
    @SerializedName("spokenText")
    public String spokenText = DEFAULT_SPOKEN_TEXT;

    /** BCP-47 language tag for TTS, e.g. {@code "en-US"}. */
    @SerializedName("ttsLanguageTag")
    public String ttsLanguageTag = "en-US";

    /** Cooldown between firings for the same rule+target key, in seconds. 0 = no cooldown. */
    @SerializedName("cooldownSeconds")
    public int cooldownSeconds = 0;

    /**
     * Restrict this rule to frames coming from a specific device, by name.
     * Empty / null = "any device" (legacy behaviour for rules saved before v1.0.32).
     */
    @SerializedName("deviceName")
    public String deviceName = "";

    public AlertRule() {}

    public String displayName() {
        if (name != null && !name.isEmpty()) return name;
        String tgt = targetIndex == TARGET_ANY ? "Any" : ("T" + (targetIndex + 1));
        return tgt + " " + operator.symbol + " " + (distanceMm / 1000.0) + "m";
    }

    public String streamLabel() {
        switch (audioStream) {
            case AudioManager.STREAM_ALARM:        return "Alarm";
            case AudioManager.STREAM_NOTIFICATION: return "Notification";
            case AudioManager.STREAM_RING:         return "Ringer";
            case AudioManager.STREAM_VOICE_CALL:   return "Call";
            case AudioManager.STREAM_MUSIC:        return "Media";
            case AudioManager.STREAM_SYSTEM:       return "System";
            default:                                return "Stream " + audioStream;
        }
    }

    public static int[] streamOptions() {
        return new int[]{
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_SYSTEM,
        };
    }

    public static String[] streamOptionLabels() {
        return new String[]{
                "Alarm",
                "Notification",
                "Ringer",
                "Voice Call",
                "Media (speaker)",
                "System",
        };
    }

    /** All vibration pattern names (for spinners). */
    public static String[] vibrationPatternOptions() {
        return new String[]{ VIB_SHORT, VIB_MEDIUM, VIB_LONG, VIB_SOS };
    }
}
