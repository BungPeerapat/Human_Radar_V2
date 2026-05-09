package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays alert sounds using {@link MediaPlayer} so we can route to a specific audio stream
 * (alarm, voice call, media, etc.) and optionally loop. Each rule gets its own player instance
 * keyed by rule id so multiple rules can fire concurrently.
 */
public class AlertSoundPlayer {

    private static final String TAG = "AlertSoundPlayer";

    private final Context appContext;
    private final Map<String, MediaPlayer> active = new HashMap<>();

    public AlertSoundPlayer(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /** Plays the rule's sound. If a player for the same rule is already running, ignored. */
    public void play(AlertRule rule) {
        if (rule.soundUri == null || rule.soundUri.isEmpty()) return;
        synchronized (active) {
            if (active.containsKey(rule.id)) return;
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setAudioAttributes(buildAttributes(rule.audioStream));
                mp.setDataSource(appContext, Uri.parse(rule.soundUri));
                mp.setLooping(rule.loop);
                mp.setOnCompletionListener(player -> {
                    if (!rule.loop) {
                        synchronized (active) {
                            active.remove(rule.id);
                        }
                        try { player.release(); } catch (Exception ignored) {}
                    }
                });
                mp.setOnErrorListener((player, what, extra) -> {
                    Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                    synchronized (active) {
                        active.remove(rule.id);
                    }
                    try { player.release(); } catch (Exception ignored) {}
                    return true;
                });
                mp.prepare();
                mp.start();
                active.put(rule.id, mp);
            } catch (IOException | IllegalStateException | SecurityException e) {
                Log.w(TAG, "Failed to play sound for rule " + rule.id, e);
            }
        }
    }

    /** Stop and release the player for this rule, if running. */
    public void stop(String ruleId) {
        synchronized (active) {
            MediaPlayer mp = active.remove(ruleId);
            if (mp != null) {
                try { mp.stop(); } catch (Exception ignored) {}
                try { mp.release(); } catch (Exception ignored) {}
            }
        }
    }

    /** Stop all currently playing alert sounds. */
    public void stopAll() {
        synchronized (active) {
            for (MediaPlayer mp : active.values()) {
                try { mp.stop(); } catch (Exception ignored) {}
                try { mp.release(); } catch (Exception ignored) {}
            }
            active.clear();
        }
    }

    private static AudioAttributes buildAttributes(int legacyStreamType) {
        int usage;
        int contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION;
        switch (legacyStreamType) {
            case android.media.AudioManager.STREAM_ALARM:
                usage = AudioAttributes.USAGE_ALARM;
                break;
            case android.media.AudioManager.STREAM_NOTIFICATION:
                usage = AudioAttributes.USAGE_NOTIFICATION;
                break;
            case android.media.AudioManager.STREAM_RING:
                usage = AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
                break;
            case android.media.AudioManager.STREAM_VOICE_CALL:
                usage = AudioAttributes.USAGE_VOICE_COMMUNICATION;
                contentType = AudioAttributes.CONTENT_TYPE_SPEECH;
                break;
            case android.media.AudioManager.STREAM_MUSIC:
                usage = AudioAttributes.USAGE_MEDIA;
                contentType = AudioAttributes.CONTENT_TYPE_MUSIC;
                break;
            default:
                usage = AudioAttributes.USAGE_NOTIFICATION_EVENT;
                break;
        }
        return new AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .setLegacyStreamType(legacyStreamType)
                .build();
    }
}
