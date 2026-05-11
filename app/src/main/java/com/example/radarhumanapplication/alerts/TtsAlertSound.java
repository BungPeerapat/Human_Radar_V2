package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Speaks the current target count when a pattern starts. Beep callbacks are
 * intentionally no-ops because TTS replaces the rhythmic beeping with a
 * single spoken phrase per event.
 */
public class TtsAlertSound implements AlertSoundSource {

    private TextToSpeech tts;
    private boolean ready = false;

    public TtsAlertSound(Context ctx) {
        tts = new TextToSpeech(ctx.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setLanguage(Locale.US);
                ready = true;
            }
        });
    }

    @Override public void onPatternStart(boolean longPrefix, int totalShorts, int currentCount) {
        if (!ready) return;
        String text;
        if (currentCount <= 0) {
            text = "Clear";
        } else if (currentCount == 1) {
            text = "1 target";
        } else {
            text = currentCount + " targets";
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert-" + System.currentTimeMillis());
    }

    @Override public void playShort(int durationMs) { /* no-op */ }
    @Override public void playLong(int durationMs)  { /* no-op */ }

    @Override public void release() {
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {}
    }
}
