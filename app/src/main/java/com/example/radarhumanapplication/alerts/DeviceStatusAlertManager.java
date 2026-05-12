package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Multi-device "online/offline" notification engine.
 *
 * <p>Tracks the last known status of every MQTT device the app has seen and, on a
 * <i>transition</i>, plays the user-picked online or offline sound (if enabled).
 * Initial status reports are <b>not</b> announced — only changes — so the user
 * isn't blasted with sound the moment the app starts.</p>
 *
 * <p>Persistence: a single SharedPreferences file ({@code device_status_alerts}) holds
 * one entry per device using key prefix {@code dev:{name}:}, plus a {@code devices}
 * StringSet listing every device name we've ever stored. Wiping the prefs file
 * resets everything cleanly.</p>
 */
public class DeviceStatusAlertManager {

    private static final String TAG = "DeviceStatusAlert";
    private static final String PREFS    = "device_status_alerts";
    private static final String KEY_SET  = "devices";

    private final Context appContext;
    private final Map<String, DeviceStatusAlertConfig> configs = new HashMap<>();
    private final Map<String, String> lastStatus = new HashMap<>();

    public DeviceStatusAlertManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        loadAll();
    }

    // ---------------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------------

    /** Returns the config for a device, creating defaults on first access. */
    public synchronized DeviceStatusAlertConfig getOrCreate(String deviceName) {
        if (TextUtils.isEmpty(deviceName)) return new DeviceStatusAlertConfig("");
        DeviceStatusAlertConfig c = configs.get(deviceName);
        if (c == null) {
            c = new DeviceStatusAlertConfig(deviceName);
            configs.put(deviceName, c);
        }
        return c.copy();
    }

    public synchronized Set<String> getKnownDevices() {
        return Collections.unmodifiableSet(new HashSet<>(configs.keySet()));
    }

    /** Persist a new/updated config for a device. */
    public synchronized void save(DeviceStatusAlertConfig cfg) {
        if (cfg == null || TextUtils.isEmpty(cfg.deviceName)) return;
        configs.put(cfg.deviceName, cfg.copy());

        SharedPreferences.Editor e = prefs().edit();
        String p = "dev:" + cfg.deviceName + ":";
        e.putBoolean(p + "on_en",   cfg.onlineEnabled);
        e.putBoolean(p + "off_en",  cfg.offlineEnabled);
        e.putString (p + "on_uri",  cfg.onlineSoundUri  == null ? "" : cfg.onlineSoundUri);
        e.putString (p + "off_uri", cfg.offlineSoundUri == null ? "" : cfg.offlineSoundUri);

        Set<String> names = new HashSet<>(prefs().getStringSet(KEY_SET, Collections.emptySet()));
        names.add(cfg.deviceName);
        e.putStringSet(KEY_SET, names);
        e.apply();
        Log.i(TAG, "Saved config for " + cfg.deviceName
                + " on(en=" + cfg.onlineEnabled + ",uri=" + summarize(cfg.onlineSoundUri) + ")"
                + " off(en=" + cfg.offlineEnabled + ",uri=" + summarize(cfg.offlineSoundUri) + ")");
    }

    /** Feed a status update from MQTT. Only transitions fire the sound. */
    public synchronized void onDeviceStatus(String deviceName, String status) {
        if (TextUtils.isEmpty(deviceName) || TextUtils.isEmpty(status)) return;
        String normalized = status.trim().toLowerCase(Locale.US);
        String previous = lastStatus.put(deviceName, normalized);
        if (previous == null) {
            // First observation — record but don't announce. Avoids blasting the user with
            // sound the moment MQTT (re)connects and the LWT retained message arrives.
            Log.d(TAG, "First status for " + deviceName + ": " + normalized + " (no sound)");
            return;
        }
        if (normalized.equals(previous)) {
            return; // no transition
        }
        Log.i(TAG, "Status change for " + deviceName + ": " + previous + " -> " + normalized);
        DeviceStatusAlertConfig cfg = configs.get(deviceName);
        if (cfg == null) return;
        if ("online".equals(normalized) && cfg.onlineEnabled) {
            playOnce(cfg.onlineSoundUri, deviceName + " online");
        } else if ("offline".equals(normalized) && cfg.offlineEnabled) {
            playOnce(cfg.offlineSoundUri, deviceName + " offline");
        }
    }

    /** Manual test from the config UI. */
    public synchronized void testPlay(String deviceName, boolean online) {
        DeviceStatusAlertConfig cfg = configs.get(deviceName);
        if (cfg == null) return;
        String uri = online ? cfg.onlineSoundUri : cfg.offlineSoundUri;
        playOnce(uri, "test " + (online ? "online" : "offline"));
    }

    // ---------------------------------------------------------------------
    //  Internals
    // ---------------------------------------------------------------------

    /** Fire-and-forget MediaPlayer that releases itself on completion / error. */
    private void playOnce(String uriStr, String label) {
        if (TextUtils.isEmpty(uriStr)) {
            Log.d(TAG, label + ": no sound configured");
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(appContext, Uri.parse(uriStr));
            mp.setOnCompletionListener(player -> {
                try { player.release(); } catch (Exception ignored) {}
            });
            mp.setOnErrorListener((player, what, extra) -> {
                Log.w(TAG, label + " play error what=" + what + " extra=" + extra);
                try { player.release(); } catch (Exception ignored) {}
                return true;
            });
            mp.prepare();
            mp.start();
            Log.i(TAG, "Playing " + label + " (" + summarize(uriStr) + ")");
        } catch (Exception e) {
            Log.w(TAG, "Failed to play " + label, e);
        }
    }

    private void loadAll() {
        SharedPreferences p = prefs();
        Set<String> names = p.getStringSet(KEY_SET, Collections.emptySet());
        for (String name : names) {
            DeviceStatusAlertConfig c = new DeviceStatusAlertConfig(name);
            String pre = "dev:" + name + ":";
            c.onlineEnabled   = p.getBoolean(pre + "on_en",   true);
            c.offlineEnabled  = p.getBoolean(pre + "off_en",  true);
            c.onlineSoundUri  = p.getString (pre + "on_uri",  "");
            c.offlineSoundUri = p.getString (pre + "off_uri", "");
            configs.put(name, c);
        }
        Log.i(TAG, "Loaded " + configs.size() + " device alert config(s)");
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String summarize(String uri) {
        if (TextUtils.isEmpty(uri)) return "(none)";
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }
}
