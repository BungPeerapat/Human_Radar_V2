package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

/**
 * Plays user-picked short/long clips selected via the Storage Access Framework.
 * Each clip is preloaded into its own {@link MediaPlayer}; replay is implemented
 * by {@code seekTo(0) + start()} so latency stays low between beeps.
 *
 * <p>If a URI is empty or fails to prepare, the corresponding beep is a silent
 * no-op (logged only) so the pattern still completes.</p>
 */
public class WavAlertSound implements AlertSoundSource {

    private static final String TAG = "WavAlertSound";

    private final Context appContext;
    private final AlertPatternConfig.Routing routing;
    private MediaPlayer shortMp;
    private MediaPlayer longMp;
    private float volume = 1f;

    /** Default constructor keeps the historical NOTIFICATION routing so old
     *  callers don't surprise-change behavior. */
    public WavAlertSound(Context ctx, int volumePct, String shortUri, String longUri) {
        this(ctx, volumePct, shortUri, longUri, AlertPatternConfig.Routing.NOTIFICATION);
    }

    public WavAlertSound(Context ctx, int volumePct, String shortUri, String longUri,
                         AlertPatternConfig.Routing routing) {
        this.appContext = ctx.getApplicationContext();
        this.routing    = routing == null ? AlertPatternConfig.Routing.NOTIFICATION : routing;
        setVolume(volumePct);
        shortMp = prepare(shortUri);
        longMp  = prepare(longUri);
    }

    public void setVolume(int volumePct) {
        volume = Math.max(0f, Math.min(1f, volumePct / 100f));
        if (shortMp != null) shortMp.setVolume(volume, volume);
        if (longMp  != null) longMp.setVolume(volume, volume);
    }

    @Override public void onPatternStart(boolean longPrefix, int totalShorts, int currentCount) {
        // no-op
    }

    @Override public void playShort(int durationMs) { trigger(shortMp, "short"); }
    @Override public void playLong(int durationMs)  { trigger(longMp,  "long");  }

    @Override public void release() {
        releaseOne(shortMp); shortMp = null;
        releaseOne(longMp);  longMp  = null;
    }

    private MediaPlayer prepare(String uriString) {
        if (TextUtils.isEmpty(uriString)) return null;
        try {
            MediaPlayer mp = new MediaPlayer();
            // Honor the user-chosen routing: NOTIFICATION respects headphones
            // (the default — sound goes only to whatever output is active),
            // ALARM forces Android to play through speaker + headphones at
            // the same time so the alert can't be muted by plugging in.
            int usage = (routing == AlertPatternConfig.Routing.ALARM)
                    ? AudioAttributes.USAGE_ALARM
                    : AudioAttributes.USAGE_NOTIFICATION_EVENT;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(appContext, Uri.parse(uriString));
            mp.prepare();
            mp.setVolume(volume, volume);
            return mp;
        } catch (Exception e) {
            Log.w(TAG, "prepare failed for " + uriString, e);
            return null;
        }
    }

    private void trigger(MediaPlayer mp, String label) {
        if (mp == null) {
            Log.d(TAG, "no clip configured for " + label);
            return;
        }
        try {
            // Always seek to 0 before starting — after the clip completes,
            // isPlaying() returns false but the cursor is at end-of-file,
            // so a plain start() plays nothing.
            mp.seekTo(0);
            mp.start();
        } catch (IllegalStateException e) {
            Log.w(TAG, label + " play failed", e);
        }
    }

    private static void releaseOne(MediaPlayer mp) {
        if (mp == null) return;
        try { mp.stop(); } catch (Exception ignored) {}
        try { mp.release(); } catch (Exception ignored) {}
    }
}
