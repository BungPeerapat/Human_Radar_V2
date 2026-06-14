package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.radarhumanapplication.MqttService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton owner of {@link AlertRule}s. Persists rules to SharedPreferences, evaluates them
 * against every incoming radar frame, and triggers sound playback with hysteresis (each rule
 * fires once per "entry" — only re-fires after the matching condition lapses).
 *
 * Cooldown is also enforced: when a rule has {@code cooldownSeconds > 0}, the rule will not
 * re-fire for that many seconds against the same target slot, regardless of hysteresis.
 *
 * <p><b>Sustained-alert teardown.</b> A looping rule keeps sounding until something tells the
 * player to stop. Three transitions must silence it, otherwise the alert hangs:
 * <ul>
 *   <li>the target stops matching the distance condition (handled inline per frame),</li>
 *   <li>the target leaves the scene entirely ({@code present == false}),</li>
 *   <li>the device goes offline / the radar link drops and frames stop arriving.</li>
 * </ul>
 * The last two are covered by {@link #onDeviceStatus(String)} (explicit offline signal) and a
 * frame-staleness watchdog ({@link #FRAME_STALE_MS}) that fires when no frames arrive — which
 * also catches a silent disconnect that never emits an "offline" status.</p>
 */
public class AlertManager
        implements MqttService.TargetListener, MqttService.StatusListener {

    private static final String TAG = "AlertManager";
    private static final String PREFS = "alert_prefs";
    private static final String KEY_RULES = "rules_v1";

    /**
     * If no radar frame arrives for this long, assume the device is offline / the link dropped
     * and silence any sustained alert. Frames stream at 10 Hz, so this is ~20 missed frames —
     * comfortably above normal jitter while still stopping a hung loop quickly.
     */
    private static final long FRAME_STALE_MS = 2_000L;

    private static final AlertManager INSTANCE = new AlertManager();
    public static AlertManager getInstance() { return INSTANCE; }

    public interface RulesChangedListener {
        void onRulesChanged();
    }

    private final Gson gson = new Gson();
    private final List<AlertRule> rules = new ArrayList<>();
    private final Set<String> firedKeys = new HashSet<>();
    /** Last fire timestamp per (rule.id + "/" + targetIndex). */
    private final Map<String, Long> lastFiredAtMillis = new HashMap<>();
    private final List<RulesChangedListener> listeners = new CopyOnWriteArrayList<>();

    private Context appContext;
    // volatile: written under synchronized(this) in attach/detach, but read bare from the
    // main-thread callback paths (onTargetsReceived / onDeviceStatus / clearFireAndStopLoop).
    private volatile AlertSoundPlayer player;
    private AlertVibrator vibrator;
    private AlertTtsPlayer tts;
    private AlertClock clock = AlertClock.SYSTEM;
    private volatile boolean attached = false;

    /** Main-looper handler driving the no-frames staleness watchdog. Null until {@link #attach}. */
    private Handler watchdog;
    private final Runnable staleFramesRunnable = this::onFramesStale;

    private AlertManager() {}

    public synchronized void attach(Context ctx) {
        if (attached) return;
        this.appContext = ctx.getApplicationContext();
        this.player = new AlertSoundPlayer(this.appContext);
        this.vibrator = new AlertVibrator(this.appContext);
        this.tts = new AlertTtsPlayer(this.appContext);
        this.watchdog = new Handler(Looper.getMainLooper());
        loadFromPrefs();
        MqttService.getInstance().addTargetListener(this);
        MqttService.getInstance().addStatusListener(this);
        attached = true;
    }

    /**
     * Release any audio / TTS resources. Call from app shutdown or fragment teardown
     * if the manager is no longer needed. Idempotent.
     */
    public synchronized void detach() {
        if (!attached) return;
        try { MqttService.getInstance().removeTargetListener(this); } catch (Exception ignored) {}
        try { MqttService.getInstance().removeStatusListener(this); } catch (Exception ignored) {}
        if (watchdog != null) watchdog.removeCallbacks(staleFramesRunnable);
        try { if (player != null) player.stopAll(); } catch (Exception ignored) {}
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
        try { if (tts != null) tts.releaseAll(); } catch (Exception ignored) {}
        attached = false;
    }

    /** Override the clock used for cooldown checks. Test-only. */
    public synchronized void setClockForTesting(AlertClock clock) {
        this.clock = (clock == null) ? AlertClock.SYSTEM : clock;
    }

    /** Reset all firing state. Test-only / used internally when rules change. */
    public synchronized void resetStateForTesting() {
        synchronized (firedKeys) { firedKeys.clear(); }
        synchronized (lastFiredAtMillis) { lastFiredAtMillis.clear(); }
    }

    /** Inject a (fake) sound player. Test-only. */
    void setPlayerForTesting(AlertSoundPlayer p) { this.player = p; }

    /** Toggle the attached flag so the status callback can be exercised. Test-only. */
    void setAttachedForTesting(boolean a) { this.attached = a; }

    /** Seed the "this key is currently firing" flag without going through MQTT. Test-only. */
    void markFiredForTesting(String key) {
        synchronized (firedKeys) { firedKeys.add(key); }
    }

    /** Whether the given key is currently flagged as firing. Test-only. */
    boolean isFiredForTesting(String key) {
        synchronized (firedKeys) { return firedKeys.contains(key); }
    }

    public List<AlertRule> getRules() {
        synchronized (rules) {
            return new ArrayList<>(rules);
        }
    }

    public void addRule(AlertRule rule) {
        synchronized (rules) {
            rules.add(rule);
        }
        persist();
        notifyChanged();
    }

    public void updateRule(AlertRule rule) {
        synchronized (rules) {
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).id.equals(rule.id)) {
                    rules.set(i, rule);
                    break;
                }
            }
        }
        // Reset fire state so updated rule re-evaluates from scratch
        clearFiredFor(rule.id);
        persist();
        notifyChanged();
    }

    public void removeRule(String id) {
        synchronized (rules) {
            rules.removeIf(r -> r.id.equals(id));
        }
        clearFiredFor(id);
        if (player != null) player.stop(id);
        persist();
        notifyChanged();
    }

    public void setEnabled(String id, boolean enabled) {
        synchronized (rules) {
            for (AlertRule r : rules) {
                if (r.id.equals(id)) {
                    r.enabled = enabled;
                    if (!enabled && player != null) player.stop(id);
                    break;
                }
            }
        }
        clearFiredFor(id);
        persist();
        notifyChanged();
    }

    public void addRulesChangedListener(RulesChangedListener l) { listeners.add(l); }
    public void removeRulesChangedListener(RulesChangedListener l) { listeners.remove(l); }

    // --- Target evaluation ---

    @Override
    public void onTargetsReceived(JsonObject data) {
        if (!attached) return;
        // A frame just arrived → the device is alive. Re-arm the staleness watchdog so a
        // sustained alert is silenced if frames later stop (offline / silent link drop).
        armStaleWatchdog();
        // Each frame: evaluate every enabled rule against either a specific target or all targets.
        List<AlertRule> snapshot;
        synchronized (rules) {
            snapshot = new ArrayList<>(rules);
        }
        // MQTT payload schema (see firmware mqtt_client.cpp::publishFrame):
        //   {"t":[{x,y,s,d,a,p}, ...], "fc":..., "ec":...}
        // Older versions of this code looked for "targets"/"present", which never matched
        // and silently disabled all distance rules. Accept both for backward compat with any
        // future schema change, but the canonical keys are "t" and "p".
        JsonArray targets = null;
        try {
            if (data.has("t")) {
                targets = data.getAsJsonArray("t");
            } else if (data.has("targets")) {
                targets = data.getAsJsonArray("targets");
            }
        } catch (Exception e) {
            return;
        }
        if (targets == null) return;

        // Per-device routing: MqttService tags frames with "_dev" = source device name.
        // Rules with a non-empty rule.deviceName are restricted to matching frames.
        String frameDev = "";
        if (data.has("_dev") && !data.get("_dev").isJsonNull()) {
            try { frameDev = data.get("_dev").getAsString(); } catch (Exception ignored) {}
        }
        for (AlertRule rule : snapshot) {
            if (!rule.enabled) continue;
            if (rule.deviceName != null && !rule.deviceName.isEmpty()
                    && !rule.deviceName.equals(frameDev)) {
                continue;   // rule is scoped to a different device
            }
            evaluateRule(rule, targets);
        }
    }

    private void evaluateRule(AlertRule rule, JsonArray targets) {
        if (rule.targetIndex == AlertRule.TARGET_ANY) {
            for (int i = 0; i < targets.size() && i < 3; i++) {
                evaluateRuleAgainst(rule, i, targets.get(i).getAsJsonObject());
            }
        } else if (rule.targetIndex >= 0 && rule.targetIndex < targets.size()) {
            evaluateRuleAgainst(rule, rule.targetIndex,
                    targets.get(rule.targetIndex).getAsJsonObject());
        }
    }

    private void evaluateRuleAgainst(AlertRule rule, int index, JsonObject target) {
        // Canonical key is "p"; fall back to "present" if some future schema reverts.
        boolean present;
        if (target.has("p")) {
            present = target.get("p").getAsBoolean();
        } else if (target.has("present")) {
            present = target.get("present").getAsBoolean();
        } else {
            present = false;
        }
        if (!present) {
            // Target slot is empty — the person left the scene entirely. Treat this exactly like
            // a non-match: clear the fired flag AND stop any looping sound. Previously this only
            // cleared the flag, so a loop that started while they were in range hung forever.
            clearFireAndStopLoop(rule, rule.id + "/" + index);
            return;
        }
        int distance = target.has("d") ? target.get("d").getAsInt()
                : (target.has("distance") ? target.get("distance").getAsInt() : computeDistance(target));
        boolean match = rule.operator.matches(distance, rule.distanceMm);
        String key = rule.id + "/" + index;
        synchronized (firedKeys) {
            if (match) {
                if (!firedKeys.contains(key)) {
                    if (isInCooldown(rule, key)) {
                        // Still mark as "fired" so we don't repeatedly evaluate, but skip side
                        // effects until the rule no longer matches and re-enters.
                        firedKeys.add(key);
                        return;
                    }
                    firedKeys.add(key);
                    recordFireTime(key);
                    fireSideEffects(rule, index, distance);
                }
            } else {
                clearFireAndStopLoop(rule, key);
            }
        }
    }

    /**
     * Clear the fired flag for one rule+target key and, if that key was firing a looping sound,
     * stop it. Shared by the "no longer matches" and "target gone" transitions so both tear a
     * sustained alert down identically. Safe to call with or without the {@code firedKeys} lock
     * already held (the monitor is reentrant). Visible for testing.
     */
    void clearFireAndStopLoop(AlertRule rule, String key) {
        synchronized (firedKeys) {
            if (firedKeys.remove(key)) {
                if (rule.loop && player != null) player.stop(rule.id);
            }
        }
    }

    /**
     * Whether the given rule+key is currently within its cooldown window.
     * Visible for testing.
     */
    boolean isInCooldown(AlertRule rule, String key) {
        if (rule.cooldownSeconds <= 0) return false;
        Long last;
        synchronized (lastFiredAtMillis) {
            last = lastFiredAtMillis.get(key);
        }
        if (last == null) return false;
        long now = clock.nowMillis();
        long elapsed = now - last;
        return elapsed < ((long) rule.cooldownSeconds) * 1000L;
    }

    private void recordFireTime(String key) {
        synchronized (lastFiredAtMillis) {
            lastFiredAtMillis.put(key, clock.nowMillis());
        }
    }

    private void fireSideEffects(AlertRule rule, int index, int distanceMm) {
        try { if (player != null) player.play(rule); } catch (Exception e) {
            Log.w(TAG, "play() failed", e);
        }
        try { if (vibrator != null) vibrator.vibrate(rule); } catch (Exception e) {
            Log.w(TAG, "vibrate() failed", e);
        }
        try { if (tts != null) tts.speak(rule, index, distanceMm); } catch (Exception e) {
            Log.w(TAG, "speak() failed", e);
        }
    }

    private static int computeDistance(JsonObject target) {
        try {
            int x = target.get("x").getAsInt();
            int y = target.get("y").getAsInt();
            return (int) Math.sqrt((double) x * x + (double) y * y);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    // --- Device offline / stale-stream teardown ---

    /**
     * MQTT device-status updates. Any status other than an explicit "online" means the device is
     * gone (offline / unknown / device switch), so stop every sustained alert immediately rather
     * than waiting for the frame watchdog. Re-entry re-fires normally once targets come back.
     *
     * <p>The {@link MqttService.StatusListener} contract carries no device name, so this silences
     * <i>all</i> sustained alerts. In a single-device setup that is exactly right. In a multi-device
     * setup where another device is still streaming, that device's rule re-fires on its very next
     * frame (~100 ms later), so the over-silence self-heals; the per-frame watchdog never trips
     * while any device is alive, so it does not over-silence.</p>
     */
    @Override
    public void onDeviceStatus(String status) {
        if (!attached) return;
        if (status == null || !"online".equalsIgnoreCase(status.trim())) {
            silenceAll();
        }
    }

    /** (Re)schedule the no-frames watchdog. Called on every frame; only active after {@link #attach}. */
    private void armStaleWatchdog() {
        if (watchdog == null) return;
        watchdog.removeCallbacks(staleFramesRunnable);
        watchdog.postDelayed(staleFramesRunnable, FRAME_STALE_MS);
    }

    /** Fired by the watchdog when {@link #FRAME_STALE_MS} elapsed with no frame. */
    private void onFramesStale() {
        Log.i(TAG, "No radar frames for " + FRAME_STALE_MS + "ms — silencing sustained alerts");
        silenceAll();
    }

    /**
     * Stop every active alert sound and reset fire state. Used when the data stream ends — device
     * offline, link dropped, or device switched — so nothing stays stuck. Visible for testing.
     */
    void silenceAll() {
        try { if (player != null) player.stopAll(); } catch (Exception ignored) {}
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        synchronized (firedKeys) {
            firedKeys.clear();
        }
    }

    private void clearFiredFor(String ruleId) {
        synchronized (firedKeys) {
            firedKeys.removeIf(k -> k.startsWith(ruleId + "/"));
        }
        synchronized (lastFiredAtMillis) {
            lastFiredAtMillis.keySet().removeIf(k -> k.startsWith(ruleId + "/"));
        }
    }

    // --- Persistence ---

    private void loadFromPrefs() {
        String json = prefs().getString(KEY_RULES, null);
        if (json == null) return;
        try {
            Type listType = new TypeToken<ArrayList<AlertRule>>(){}.getType();
            List<AlertRule> loaded = gson.fromJson(json, listType);
            if (loaded != null) {
                synchronized (rules) {
                    rules.clear();
                    rules.addAll(loaded);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load rules", e);
        }
    }

    private void persist() {
        if (appContext == null) return;
        try {
            String json;
            synchronized (rules) {
                json = gson.toJson(rules);
            }
            prefs().edit().putString(KEY_RULES, json).apply();
        } catch (Exception e) {
            Log.w(TAG, "Persist failed", e);
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void notifyChanged() {
        for (RulesChangedListener l : listeners) {
            try { l.onRulesChanged(); } catch (Exception ignored) {}
        }
    }

    /** Distances (mm) of currently enabled rules — used by RadarView to draw arcs. */
    public List<Integer> getEnabledDistancesMm() {
        List<Integer> out = new ArrayList<>();
        synchronized (rules) {
            for (AlertRule r : rules) {
                if (r.enabled) out.add(r.distanceMm);
            }
        }
        Collections.sort(out);
        return out;
    }
}
