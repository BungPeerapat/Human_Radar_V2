package com.example.radarhumanapplication.alerts;

import android.content.Context;
import android.content.SharedPreferences;
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
 */
public class AlertManager implements MqttService.TargetListener {

    private static final String TAG = "AlertManager";
    private static final String PREFS = "alert_prefs";
    private static final String KEY_RULES = "rules_v1";

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
    private AlertSoundPlayer player;
    private AlertVibrator vibrator;
    private AlertTtsPlayer tts;
    private AlertClock clock = AlertClock.SYSTEM;
    private boolean attached = false;

    private AlertManager() {}

    public synchronized void attach(Context ctx) {
        if (attached) return;
        this.appContext = ctx.getApplicationContext();
        this.player = new AlertSoundPlayer(this.appContext);
        this.vibrator = new AlertVibrator(this.appContext);
        this.tts = new AlertTtsPlayer(this.appContext);
        loadFromPrefs();
        MqttService.getInstance().addTargetListener(this);
        attached = true;
    }

    /**
     * Release any audio / TTS resources. Call from app shutdown or fragment teardown
     * if the manager is no longer needed. Idempotent.
     */
    public synchronized void detach() {
        if (!attached) return;
        try { MqttService.getInstance().removeTargetListener(this); } catch (Exception ignored) {}
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
        // Each frame: evaluate every enabled rule against either a specific target or all targets.
        List<AlertRule> snapshot;
        synchronized (rules) {
            snapshot = new ArrayList<>(rules);
        }
        if (!data.has("targets")) return;
        JsonArray targets;
        try {
            targets = data.getAsJsonArray("targets");
        } catch (Exception e) {
            return;
        }

        for (AlertRule rule : snapshot) {
            if (!rule.enabled) continue;
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
        boolean present = target.has("present") && target.get("present").getAsBoolean();
        if (!present) {
            unsetFired(rule.id, index);
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
                if (firedKeys.remove(key)) {
                    if (rule.loop && player != null) player.stop(rule.id);
                }
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

    private void unsetFired(String ruleId, int index) {
        String key = ruleId + "/" + index;
        synchronized (firedKeys) {
            firedKeys.remove(key);
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
