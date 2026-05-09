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
}
