package com.example.radarhumanapplication.transport;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.radarhumanapplication.MqttService;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Singleton orchestrator that owns one {@link DirectWsTransport} per ESP32
 * the app knows about and feeds the resulting frames into the shared
 * {@link MqttService#dispatchTargetsToListeners(JsonObject)} fan-out — the
 * same path MQTT frames use, so {@code RadarView}, {@code AlertManager},
 * {@code SessionRecorder} etc. all keep working transparently.
 *
 * <h3>Decision rules</h3>
 * <ul>
 *   <li><b>CLOUD mode</b>: do nothing — every direct connection stays closed.</li>
 *   <li><b>LAN mode</b>: open a WS to every device whose IP we know
 *       (MQTT-discovered or manually configured). MQTT can stay open for
 *       discovery, but radar frames come from WS only — once a device is
 *       LAN-active we mute MQTT for that device.</li>
 *   <li><b>HYBRID</b>: TCP-probe port 81 for each known IP; if reachable
 *       within {@code tcpProbeTimeoutMs} → switch that device to LAN. If
 *       the WS dies and stays silent past {@code fallbackGraceMs} →
 *       unmute MQTT for that device so frames keep arriving via cloud.</li>
 * </ul>
 *
 * <p>Re-evaluation triggers: settings change, new device discovered, WS
 * state change. There is no continuous polling loop — every action is
 * event-driven.
 */
public final class HybridTransportManager
        implements TransportSettings.Listener, MqttService.DiscoveryListener,
                   DirectWsTransport.Listener {

    private static final String TAG = "HybridTransport";
    /** Firmware exposes the radar WS on port 81 (web_server.h: WEBSOCKET_PORT). */
    public  static final int    DEFAULT_WS_PORT = 81;

    private static volatile HybridTransportManager instance;

    public static HybridTransportManager get(Context ctx) {
        HybridTransportManager local = instance;
        if (local != null) return local;
        synchronized (HybridTransportManager.class) {
            if (instance == null) {
                instance = new HybridTransportManager(ctx.getApplicationContext());
            }
            return instance;
        }
    }

    public interface StatusListener {
        /** Called on main thread whenever the live transport map changes. */
        void onTransportStatusChanged(Map<String, DeviceTransportStatus> snapshot);
    }

    /** Per-device snapshot for the UI badge / settings screen. */
    public static final class DeviceTransportStatus {
        public final String deviceName;
        public final String ip;
        public final DirectWsTransport.State wsState;
        /** Effective transport feeding RadarView for this device right now. */
        public final TransportLane lane;
        public DeviceTransportStatus(String deviceName, String ip,
                                     DirectWsTransport.State wsState,
                                     TransportLane lane) {
            this.deviceName = deviceName == null ? "" : deviceName;
            this.ip         = ip == null ? "" : ip;
            this.wsState    = wsState;
            this.lane       = lane;
        }
    }

    /** Which lane is delivering frames for a given device right now. */
    public enum TransportLane { CLOUD_MQTT, LAN_WS, NONE }

    private final Context appContext;
    private final TransportSettings settings;
    private final MqttService mqtt = MqttService.getInstance();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();

    /** deviceName -> active WS transport (only present when running). */
    private final Map<String, DirectWsTransport> wsByDevice = new HashMap<>();
    /** deviceName -> last-known lane chosen by the manager. */
    private final Map<String, TransportLane> laneByDevice = new HashMap<>();
    /** deviceName -> last-known IP for direct connect. */
    private final Map<String, String> ipByDevice = new HashMap<>();

    private final java.util.List<StatusListener> statusListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile boolean started = false;

    private HybridTransportManager(Context appContext) {
        this.appContext = appContext;
        this.settings   = TransportSettings.get(appContext);
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        settings.addListener(this);
        mqtt.addDiscoveryListener(this);
        // Mute live-target fan-out for LAN-active devices so frames don't
        // double up. We do per-device muting via the _transport tag inside
        // dispatchTargetsToListeners — kept on the MqttService side.
        reconcile();
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        settings.removeListener(this);
        mqtt.removeDiscoveryListener(this);
        for (DirectWsTransport t : wsByDevice.values()) {
            try { t.close(); } catch (Exception ignored) {}
        }
        wsByDevice.clear();
        laneByDevice.clear();
        notifyStatus();
    }

    public void addStatusListener(StatusListener l) {
        if (l != null) statusListeners.add(l);
    }
    public void removeStatusListener(StatusListener l) { statusListeners.remove(l); }

    /** Snapshot for the settings UI / status badge. */
    public synchronized Map<String, DeviceTransportStatus> snapshot() {
        Map<String, DeviceTransportStatus> out = new java.util.LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        names.addAll(laneByDevice.keySet());
        names.addAll(ipByDevice.keySet());
        for (String n : names) {
            DirectWsTransport t = wsByDevice.get(n);
            out.put(n, new DeviceTransportStatus(
                    n,
                    ipByDevice.getOrDefault(n, ""),
                    t != null ? t.getState() : DirectWsTransport.State.DISCONNECTED,
                    laneByDevice.getOrDefault(n, TransportLane.NONE)));
        }
        return out;
    }

    private void notifyStatus() {
        Map<String, DeviceTransportStatus> snap = snapshot();
        for (StatusListener l : statusListeners) {
            try { main.post(() -> l.onTransportStatusChanged(snap)); }
            catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------
    //  Reconcile: pick the right transport per known device
    // ------------------------------------------------------------------

    /** Re-evaluate every known device against current settings. Cheap and
     *  idempotent — safe to call from any listener event. */
    public synchronized void reconcile() {
        refreshIpsFromDiscovery();
        if (!settings.isEnabled()) {
            shutdownAllWs("transport disabled");
            applyAllLanes(TransportLane.CLOUD_MQTT);
            return;
        }
        TransportMode mode = settings.getMode();
        switch (mode) {
            case CLOUD:
                shutdownAllWs("CLOUD mode");
                applyAllLanes(TransportLane.CLOUD_MQTT);
                break;
            case LAN:
                openWsForAllKnown();
                applyAllLanes(TransportLane.LAN_WS);
                break;
            case HYBRID:
            default:
                reconcileHybrid();
                break;
        }
        notifyStatus();
    }

    private void refreshIpsFromDiscovery() {
        // MQTT-discovered IPs (humanradar/+/info has ip field).
        for (MqttService.DiscoveredDevice d : mqtt.getDiscoveredDevices()) {
            if (d == null || d.deviceName == null || d.deviceName.isEmpty()) continue;
            if (d.ip == null || d.ip.isEmpty()) continue;
            ipByDevice.put(d.deviceName, d.ip);
        }
        // Manually-entered IPs — used as deviceName == ip placeholder so the
        // user can still reach a device whose MQTT is unreachable.
        for (String ip : settings.getManualDirectIps()) {
            if (ip == null || ip.isEmpty()) continue;
            String synthetic = "manual:" + ip;
            ipByDevice.putIfAbsent(synthetic, ip);
        }
    }

    private void openWsForAllKnown() {
        for (Map.Entry<String, String> e : ipByDevice.entrySet()) {
            openWsForDevice(e.getKey(), e.getValue());
        }
    }

    private void reconcileHybrid() {
        // Probe each known IP in parallel; open WS where reachable, otherwise
        // cloud takes over. Existing healthy WS stays open without re-probing.
        for (Map.Entry<String, String> e : ipByDevice.entrySet()) {
            final String name = e.getKey();
            final String ip   = e.getValue();
            DirectWsTransport existing = wsByDevice.get(name);
            if (existing != null && existing.getState() == DirectWsTransport.State.CONNECTED) {
                // Already LAN — leave it.
                continue;
            }
            io.execute(() -> {
                if (probeTcp(ip, DEFAULT_WS_PORT, settings.getTcpProbeTimeoutMs())) {
                    main.post(() -> openWsForDevice(name, ip));
                } else {
                    main.post(() -> {
                        closeWsForDevice(name, "TCP probe failed");
                        setLane(name, TransportLane.CLOUD_MQTT);
                        notifyStatus();
                    });
                }
            });
        }
    }

    private static boolean probeTcp(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private synchronized void openWsForDevice(String name, String ip) {
        if (!started) return;
        DirectWsTransport existing = wsByDevice.get(name);
        if (existing != null) {
            if (ip.equals(existing.getIp())) return;       // already targeting this IP
            try { existing.close(); } catch (Exception ignored) {}
            wsByDevice.remove(name);
        }
        DirectWsTransport t = new DirectWsTransport(
                name, ip, DEFAULT_WS_PORT,
                settings.getWsReconnectMs(), this);
        wsByDevice.put(name, t);
        t.connect();
        setLane(name, TransportLane.LAN_WS);
        // Mute MQTT for this device — we don't want both transports
        // delivering the same frame.
        mqtt.muteMqttFor(name, true);
        if (settings.isVerboseLog()) {
            Log.i(TAG, "Opening WS to " + name + " @ " + ip);
        }
    }

    private synchronized void closeWsForDevice(String name, String reason) {
        DirectWsTransport existing = wsByDevice.remove(name);
        if (existing != null) {
            try { existing.close(); } catch (Exception ignored) {}
        }
        mqtt.muteMqttFor(name, false);
        if (settings.isVerboseLog()) {
            Log.i(TAG, "Closing WS for " + name + " (" + reason + ")");
        }
    }

    private synchronized void shutdownAllWs(String reason) {
        for (String name : new java.util.ArrayList<>(wsByDevice.keySet())) {
            closeWsForDevice(name, reason);
        }
    }

    private synchronized void applyAllLanes(TransportLane lane) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(ipByDevice.keySet());
        names.addAll(laneByDevice.keySet());
        for (String n : names) setLane(n, lane);
    }

    private synchronized void setLane(String name, TransportLane lane) {
        TransportLane prev = laneByDevice.put(name, lane);
        if (prev != lane) {
            if (settings.isVerboseLog()) {
                Log.i(TAG, "Lane " + name + ": " + prev + " -> " + lane);
            }
            // Always surface lane flips in the in-app log buffer — they're
            // the main thing developers want to see when triaging "why is
            // my radar feed coming via cloud instead of LAN?".
            mqtt.injectAppLog("INFO", "TRANSPORT",
                    name + ": " + prev + " → " + lane);
        }
    }

    // ------------------------------------------------------------------
    //  Listener wires
    // ------------------------------------------------------------------

    @Override
    public void onTransportSettingsChanged(TransportSettings s) {
        reconcile();
    }

    @Override
    public void onDeviceDiscovered(String deviceName, String status, long lastSeenMs) {
        // New device or status flip — re-evaluate (cheap).
        reconcile();
    }

    @Override
    public void onFrame(String deviceName, JsonObject data) {
        // Route LAN frames into the same fan-out MQTT uses — RadarView, alerts,
        // recorder all keep working without knowing where the bytes came from.
        mqtt.dispatchTargetsToListeners(data);
    }

    @Override
    public void onState(String deviceName, DirectWsTransport.State state, String reason) {
        if (!started) return;
        if (state == DirectWsTransport.State.CONNECTED) {
            setLane(deviceName, TransportLane.LAN_WS);
            mqtt.muteMqttFor(deviceName, true);
        } else {
            // WS dropped — fall back to cloud while it tries to reconnect.
            setLane(deviceName, TransportLane.CLOUD_MQTT);
            mqtt.muteMqttFor(deviceName, false);
        }
        notifyStatus();
    }
}
