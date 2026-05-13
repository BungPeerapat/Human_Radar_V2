package com.example.radarhumanapplication.health;

import android.os.Handler;
import android.os.Looper;

import com.example.radarhumanapplication.MqttService;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sends "health" MQTT commands to one or all discovered ESP32 devices and
 * collects responses within a deadline.
 *
 * <p>For "Check All", devices that don't respond within {@code timeoutMs} are
 * flagged offline via {@link MqttService#markDeviceOffline(String)} so the
 * radar canvas (primary + secondary overlays) sweeps them off automatically.
 *
 * <p>Single-instance per session — kept off the main thread only for the
 * scheduled timeout; all listener callbacks fire on the main thread.
 */
public class HealthCheckManager implements MqttService.AnyCmdAckListener {

    /** Per-device snapshot collected during a health-check round. */
    public static final class HealthResult {
        public final String deviceName;
        public final boolean responded;
        public final long uptimeSec;
        public final long heapBytes;
        public final long rssiDb;
        public final String fw;
        public final String ip;

        public HealthResult(String deviceName, boolean responded,
                            long uptimeSec, long heapBytes, long rssiDb,
                            String fw, String ip) {
            this.deviceName = deviceName;
            this.responded = responded;
            this.uptimeSec = uptimeSec;
            this.heapBytes = heapBytes;
            this.rssiDb = rssiDb;
            this.fw = fw == null ? "" : fw;
            this.ip = ip == null ? "" : ip;
        }
    }

    /** Callback for a finished health-check round. */
    public interface ResultListener {
        void onHealthCheckFinished(List<HealthResult> results, Set<String> offlineDevices);
    }

    private static final HealthCheckManager INSTANCE = new HealthCheckManager();
    public static HealthCheckManager getInstance() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final MqttService mqtt = MqttService.getInstance();

    /** Device → result for the currently-running round; null when idle. */
    private Map<String, HealthResult> inflight;
    private ResultListener pendingListener;
    private Runnable timeoutTask;
    private boolean attached = false;

    private HealthCheckManager() {}

    private void attachIfNeeded() {
        if (attached) return;
        mqtt.addAnyCmdAckListener(this);
        attached = true;
    }

    public boolean isRunning() { return inflight != null; }

    /** Send a health probe to one device. Convenience over {@link #checkAll}. */
    public synchronized boolean checkOne(String deviceName, long timeoutMs,
                                         ResultListener listener) {
        if (deviceName == null || deviceName.isEmpty()) return false;
        return checkMany(Collections.singletonList(deviceName), timeoutMs,
                listener, /*sweepOffline=*/ false);
    }

    /**
     * Send a health probe to every discovered device. Devices that fail to
     * respond within {@code timeoutMs} are marked offline so the radar UI
     * drops their stale targets.
     */
    public synchronized boolean checkAll(long timeoutMs, ResultListener listener) {
        List<MqttService.DiscoveredDevice> known = mqtt.getDiscoveredDevices();
        List<String> names = new ArrayList<>(known.size());
        for (MqttService.DiscoveredDevice d : known) {
            if (d != null && d.deviceName != null && !d.deviceName.isEmpty()) {
                names.add(d.deviceName);
            }
        }
        return checkMany(names, timeoutMs, listener, /*sweepOffline=*/ true);
    }

    private synchronized boolean checkMany(List<String> deviceNames, long timeoutMs,
                                           ResultListener listener,
                                           boolean sweepOffline) {
        if (!mqtt.isConnected()) return false;
        if (deviceNames == null || deviceNames.isEmpty()) return false;
        if (inflight != null) return false;          // round already running

        attachIfNeeded();
        inflight = new LinkedHashMap<>();
        for (String n : deviceNames) {
            inflight.put(n, new HealthResult(n, false, 0, 0, 0, "", ""));
        }
        pendingListener = listener;

        for (String n : deviceNames) {
            mqtt.sendCommandTo(n, "health");
        }

        final boolean sweep = sweepOffline;
        timeoutTask = () -> finish(sweep);
        main.postDelayed(timeoutTask, Math.max(500L, timeoutMs));
        return true;
    }

    @Override
    public void onAnyCmdAck(String deviceName, JsonObject ack) {
        if (deviceName == null || ack == null) return;
        Map<String, HealthResult> snap;
        synchronized (this) {
            if (inflight == null || !inflight.containsKey(deviceName)) return;
            String cmd = ack.has("cmd") ? safeString(ack, "cmd") : "";
            // Only health-shaped acks count as a positive response.
            if (!"health".equalsIgnoreCase(cmd)) return;
            HealthResult r = new HealthResult(
                    deviceName,
                    /*responded=*/ true,
                    ack.has("uptime") ? safeLong(ack, "uptime") : 0,
                    ack.has("heap") ? safeLong(ack, "heap") : 0,
                    ack.has("rssi") ? safeLong(ack, "rssi") : 0,
                    safeString(ack, "fw"),
                    safeString(ack, "ip"));
            inflight.put(deviceName, r);
            // If every device has responded, finish early.
            boolean allIn = true;
            for (HealthResult v : inflight.values()) {
                if (!v.responded) { allIn = false; break; }
            }
            if (!allIn) return;
            snap = inflight;
        }
        // Drop the timeout and finish on the main thread.
        main.post(() -> finish(/*sweepOffline=*/ false));
    }

    private synchronized void finish(boolean sweepOffline) {
        if (inflight == null) return;
        if (timeoutTask != null) {
            main.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
        List<HealthResult> results = new ArrayList<>(inflight.values());
        Set<String> offline = new HashSet<>();
        for (HealthResult r : results) {
            if (!r.responded) offline.add(r.deviceName);
        }
        if (sweepOffline) {
            for (String name : offline) {
                mqtt.markDeviceOffline(name);
            }
        }
        ResultListener cb = pendingListener;
        inflight = null;
        pendingListener = null;
        if (cb != null) {
            main.post(() -> cb.onHealthCheckFinished(results, offline));
        }
    }

    private static String safeString(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
        catch (Exception e) { return ""; }
    }

    private static long safeLong(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L; }
        catch (Exception e) { return 0L; }
    }
}
