package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Singleton-style TTS player for alert rules. Lazily initializes a single {@link TextToSpeech}
 * engine, resolves placeholders from rule context, and routes audio to the rule's stream.
 *
 * Lifecycle: call {@link #releaseAll()} when the owning {@link AlertManager} is detached.
 */
public class AlertTtsPlayer {

    private static final String TAG = "AlertTtsPlayer";

    private final Context appContext;
    private TextToSpeech tts;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private Locale currentLocale = Locale.US;

    public AlertTtsPlayer(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /** Lazily initialize the engine. Subsequent calls are no-ops. */
    private synchronized void ensureEngine() {
        if (tts != null) return;
        try {
            tts = new TextToSpeech(appContext, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        int result = tts.setLanguage(currentLocale);
                        if (result == TextToSpeech.LANG_MISSING_DATA
                                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.US);
                        }
                        ready.set(true);
                    } catch (Exception e) {
                        Log.w(TAG, "TTS setLanguage failed", e);
                    }
                } else {
                    Log.w(TAG, "TTS init failed status=" + status);
                }
            });
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onDone(String utteranceId) {}
                @Override public void onError(String utteranceId) {
                    Log.w(TAG, "TTS error for utterance " + utteranceId);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to construct TextToSpeech", e);
        }
    }

    /**
     * Speak the rule's text (after placeholder substitution) using the rule's audio stream.
     * Distance is the matched distance in millimetres. Target index is 0..2 (or -1 for ANY,
     * which renders as "Any").
     */
    public void speak(AlertRule rule, int targetIndex, int distanceMm) {
        if (rule == null || !rule.speak) return;
        String template = TextUtils.isEmpty(rule.spokenText)
                ? AlertRule.DEFAULT_SPOKEN_TEXT : rule.spokenText;
        String resolved = renderTemplate(template, rule, targetIndex, distanceMm);
        if (TextUtils.isEmpty(resolved)) return;

        applyLanguageIfNeeded(rule.ttsLanguageTag);
        ensureEngine();
        if (tts == null) return;

        try {
            int stream = rule.audioStream;
            // Route via AudioAttributes when available, plus legacy stream as fallback.
            try {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usageForStream(stream))
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(stream)
                        .build());
            } catch (Exception ignored) {}

            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream);
            String utteranceId = "alert-" + rule.id + "-" + System.currentTimeMillis();
            tts.speak(resolved, TextToSpeech.QUEUE_ADD, params, utteranceId);
        } catch (Exception e) {
            Log.w(TAG, "speak() failed", e);
        }
    }

    /** Speak arbitrary text once — used by the editor's Test button. */
    public void speakOnce(String text, int audioStream, String languageTag) {
        if (TextUtils.isEmpty(text)) return;
        applyLanguageIfNeeded(languageTag);
        ensureEngine();
        if (tts == null) return;
        try {
            try {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usageForStream(audioStream))
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(audioStream)
                        .build());
            } catch (Exception ignored) {}
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, audioStream);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "alert-test-" + System.currentTimeMillis());
        } catch (Exception e) {
            Log.w(TAG, "speakOnce() failed", e);
        }
    }

    private void applyLanguageIfNeeded(String languageTag) {
        Locale next = parseLocale(languageTag);
        if (next.equals(currentLocale)) return;
        currentLocale = next;
        if (tts != null && ready.get()) {
            try {
                int result = tts.setLanguage(next);
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
            } catch (Exception ignored) {}
        }
    }

    private static Locale parseLocale(String tag) {
        if (TextUtils.isEmpty(tag)) return Locale.US;
        try {
            return Locale.forLanguageTag(tag);
        } catch (Exception e) {
            return Locale.US;
        }
    }

    private static int usageForStream(int stream) {
        switch (stream) {
            case AudioManager.STREAM_ALARM:        return AudioAttributes.USAGE_ALARM;
            case AudioManager.STREAM_NOTIFICATION: return AudioAttributes.USAGE_NOTIFICATION;
            case AudioManager.STREAM_RING:         return AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
            case AudioManager.STREAM_VOICE_CALL:   return AudioAttributes.USAGE_VOICE_COMMUNICATION;
            case AudioManager.STREAM_MUSIC:        return AudioAttributes.USAGE_MEDIA;
            default:                                return AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY;
        }
    }

    /** Render a spokenText template with placeholder substitution. Public for testing. */
    public static String renderTemplate(String template, AlertRule rule,
                                        int targetIndex, int distanceMm) {
        if (template == null) return "";
        String targetLabel = (targetIndex < 0) ? "Any" : String.valueOf(targetIndex + 1);
        String distanceLabel = String.format(Locale.US, "%.1f", distanceMm / 1000.0);
        String operatorLabel = rule != null && rule.operator != null
                ? rule.operator.symbol : "";
        return template
                .replace("{target}", targetLabel)
                .replace("{distance}", distanceLabel)
                .replace("{operator}", operatorLabel);
    }

    /** Stop any in-flight speech and shut down the engine. Safe to call multiple times. */
    public synchronized void releaseAll() {
        if (tts == null) return;
        try { tts.stop(); } catch (Exception ignored) {}
        try { tts.shutdown(); } catch (Exception ignored) {}
        tts = null;
        ready.set(false);
    }
}
