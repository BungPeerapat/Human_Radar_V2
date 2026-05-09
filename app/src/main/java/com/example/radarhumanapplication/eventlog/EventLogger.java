package com.example.radarhumanapplication.eventlog;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.radarhumanapplication.MqttService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton detection-event logger. Listens to {@link MqttService} target frames, runs each
 * target through an {@link EventDetector}, and keeps the last 200 events in memory plus a rolling
 * persisted copy in {@code event_log_prefs}.
 */
public class EventLogger implements MqttService.TargetListener {

    private static final String TAG = "EventLogger";
    private static final String PREFS = "event_log_prefs";
    private static final String KEY_EVENTS = "events_v1";
    private static final int MAX_EVENTS = 200;

    private static final EventLogger INSTANCE = new EventLogger();
    public static EventLogger getInstance() { return INSTANCE; }

    public interface EventListener {
        void onEvent(Event e);
    }

    private final Gson gson = new Gson();
    private final Deque<Event> events = new ArrayDeque<>();
    private final EventDetector[] detectors = new EventDetector[] {
            new EventDetector(0), new EventDetector(1), new EventDetector(2)
    };
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    private Context appContext;
    private boolean attached;

    private EventLogger() {}

    public synchronized void attach(Context ctx) {
        if (attached) return;
        this.appContext = ctx.getApplicationContext();
        loadFromPrefs();
        MqttService.getInstance().addTargetListener(this);
        attached = true;
    }

    public void addEventListener(EventListener l) { listeners.add(l); }
    public void removeEventListener(EventListener l) { listeners.remove(l); }

    public synchronized List<Event> getEvents() {
        return new ArrayList<>(events);
    }

    public synchronized void clear() {
        events.clear();
        persist();
    }

    @Override
    public void onTargetsReceived(JsonObject data) {
        JsonArray arr = extractTargets(data);
        if (arr == null) return;
        long ts = System.currentTimeMillis();
        for (int i = 0; i < Math.min(arr.size(), detectors.length); i++) {
            JsonObject t = arr.get(i).getAsJsonObject();
            boolean present = (t.has("p") && t.get("p").getAsBoolean())
                    || (t.has("present") && t.get("present").getAsBoolean());
            int distance;
            if (t.has("d")) distance = t.get("d").getAsInt();
            else if (t.has("distance")) distance = t.get("distance").getAsInt();
            else distance = computeDistance(t);
            for (Event e : detectors[i].observe(ts, present, distance)) {
                addEvent(e);
            }
        }
    }

    private static JsonArray extractTargets(JsonObject data) {
        JsonElement el = null;
        if (data.has("t")) el = data.get("t");
        else if (data.has("targets")) el = data.get("targets");
        if (el == null || !el.isJsonArray()) return null;
        return el.getAsJsonArray();
    }

    private static int computeDistance(JsonObject target) {
        try {
            int x = target.get("x").getAsInt();
            int y = target.get("y").getAsInt();
            return (int) Math.sqrt((double) x * x + (double) y * y);
        } catch (Exception e) {
            return 0;
        }
    }

    private void addEvent(Event e) {
        synchronized (this) {
            events.addLast(e);
            while (events.size() > MAX_EVENTS) {
                events.pollFirst();
            }
            persist();
        }
        for (EventListener l : listeners) {
            try { l.onEvent(e); } catch (Exception ignored) {}
        }
    }

    private void loadFromPrefs() {
        if (appContext == null) return;
        try {
            String json = prefs().getString(KEY_EVENTS, null);
            if (json == null) return;
            Type t = new TypeToken<ArrayList<Event>>(){}.getType();
            List<Event> loaded = gson.fromJson(json, t);
            if (loaded != null) {
                events.clear();
                events.addAll(loaded);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load events", e);
        }
    }

    private void persist() {
        if (appContext == null) return;
        try {
            prefs().edit().putString(KEY_EVENTS, gson.toJson(new ArrayList<>(events))).apply();
        } catch (Exception e) {
            Log.w(TAG, "Persist events failed", e);
        }
    }

    private SharedPreferences prefs() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
