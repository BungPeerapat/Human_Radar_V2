package com.example.radarhumanapplication;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.radarhumanapplication.alerts.AlertPatternConfig;
import com.example.radarhumanapplication.alerts.AlertPatternPlayer;
import com.example.radarhumanapplication.alerts.DeviceStatusAlertManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MQTT service for communicating with ESP32 Human Radar.
 *
 * Topics:
 *   humanradar/{name}/targets      - radar data (subscribe)
 *   humanradar/{name}/status       - online/offline (subscribe)
 *   humanradar/{name}/log          - log entries (subscribe)
 *   humanradar/{name}/config       - send config (publish)
 *   humanradar/{name}/config/ack   - config response (subscribe)
 *   humanradar/{name}/cmd          - send commands (publish)
 *   humanradar/{name}/cmd/ack      - command response (subscribe)
 */
public class MqttService {
    private static final String TAG = "MqttService";
    private static final MqttService instance = new MqttService();

    private Mqtt3AsyncClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final List<LogEntry> logBuffer = new CopyOnWriteArrayList<>();
    private static final int MAX_LOG_ENTRIES = 500;

    private String brokerHost = "";
    private int brokerPort = 1883;
    private String deviceName = "HumanRadar";
    private String username = "";
    private String password = "";
    /** Written from HiveMQ callback threads, read from main thread — must be volatile. */
    private volatile boolean connected = false;
    private Context appContext;
    private AlertPatternPlayer alertPlayer;
    private DeviceStatusAlertManager deviceStatusAlerts;
    /** When true, live MQTT target frames are not fanned out to TargetListeners
     *  (used by SessionReplayer to avoid mixing live + replay frames). */
    private volatile boolean liveTargetMuted = false;

    // Listeners
    public interface ConnectionListener {
        void onConnected();
        void onDisconnected(String reason);
    }

    public interface TargetListener {
        void onTargetsReceived(JsonObject data);
    }

    public interface StatusListener {
        void onDeviceStatus(String status);
    }

    public interface LogListener {
        void onLogReceived(LogEntry entry);
    }

    public interface ConfigAckListener {
        void onConfigAck(JsonObject ack);
    }

    public interface CmdAckListener {
        void onCmdAck(JsonObject ack);
    }

    /** Discovery: invoked when a humanradar/+/status retained or live message arrives. */
    public interface DiscoveryListener {
        void onDeviceDiscovered(String deviceName, String status, long lastSeenMs);
    }

    /** A device seen on the wildcard discovery feed. */
    public static class DiscoveredDevice {
        public final String deviceName;
        public String status;          // "online" / "offline" / raw payload
        public long   lastSeenMs;
        /** Device's HTTP IP, populated from the humanradar/{name}/info retained message. */
        public String ip = "";
        public String fw = "";
        public String mac = "";

        public DiscoveredDevice(String deviceName, String status, long lastSeenMs) {
            this.deviceName = deviceName;
            this.status     = status;
            this.lastSeenMs = lastSeenMs;
        }

        public boolean isOnline() { return "online".equalsIgnoreCase(status); }
    }

    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final List<TargetListener> targetListeners = new CopyOnWriteArrayList<>();
    private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final List<LogListener> logListeners = new CopyOnWriteArrayList<>();
    private final List<ConfigAckListener> configAckListeners = new CopyOnWriteArrayList<>();
    private final List<CmdAckListener> cmdAckListeners = new CopyOnWriteArrayList<>();
    private final List<DiscoveryListener> discoveryListeners = new CopyOnWriteArrayList<>();
    /** Devices observed via wildcard {@code humanradar/+/status} since connect. */
    private final Map<String, DiscoveredDevice> discoveredDevices = new ConcurrentHashMap<>();

    private String deviceStatus = "unknown";
    private JsonObject lastTargetData;

    public static MqttService getInstance() {
        return instance;
    }

    private MqttService() {}

    // Listener registration
    public void addConnectionListener(ConnectionListener l) { connectionListeners.add(l); }
    public void removeConnectionListener(ConnectionListener l) { connectionListeners.remove(l); }
    public void addTargetListener(TargetListener l) { targetListeners.add(l); }
    public void removeTargetListener(TargetListener l) { targetListeners.remove(l); }
    public void addStatusListener(StatusListener l) { statusListeners.add(l); }
    public void removeStatusListener(StatusListener l) { statusListeners.remove(l); }
    public void addLogListener(LogListener l) { logListeners.add(l); }
    public void removeLogListener(LogListener l) { logListeners.remove(l); }
    public void addConfigAckListener(ConfigAckListener l) { configAckListeners.add(l); }
    public void removeConfigAckListener(ConfigAckListener l) { configAckListeners.remove(l); }
    public void addCmdAckListener(CmdAckListener l) { cmdAckListeners.add(l); }
    public void removeCmdAckListener(CmdAckListener l) { cmdAckListeners.remove(l); }
    public void addDiscoveryListener(DiscoveryListener l) { discoveryListeners.add(l); }
    public void removeDiscoveryListener(DiscoveryListener l) { discoveryListeners.remove(l); }

    /** Snapshot of every device seen via humanradar/+/status, newest-first by lastSeen. */
    public List<DiscoveredDevice> getDiscoveredDevices() {
        List<DiscoveredDevice> snap = new ArrayList<>(discoveredDevices.values());
        Collections.sort(snap, (a, b) -> Long.compare(b.lastSeenMs, a.lastSeenMs));
        return snap;
    }

    /** Just the devices currently reporting "online". */
    public List<DiscoveredDevice> getOnlineDevices() {
        List<DiscoveredDevice> out = new ArrayList<>();
        for (DiscoveredDevice d : discoveredDevices.values()) {
            if (d.isOnline()) out.add(d);
        }
        Collections.sort(out, Comparator.comparing(d -> d.deviceName.toLowerCase()));
        return out;
    }

    // State getters
    public boolean isConnected() { return connected; }
    public String getDeviceStatus() { return deviceStatus; }
    public JsonObject getLastTargetData() { return lastTargetData; }
    public List<LogEntry> getLogBuffer() { return new ArrayList<>(logBuffer); }
    public String getDeviceName() { return deviceName; }
    public String getBrokerHost() { return brokerHost; }
    public int getBrokerPort() { return brokerPort; }

    public void configure(String host, int port, String device, String user, String pass) {
        this.brokerHost = host;
        this.brokerPort = port;
        this.deviceName = device;
        this.username = user;
        this.password = pass;
    }

    /**
     * "One-tap switch active device" — change which ESP32 the per-device topic
     * subscriptions point at, without making the user open a config screen.
     * Disconnects and reconnects against the same broker with the new name so
     * the {@code humanradar/<name>/...} subscriptions refresh. Also syncs the
     * active {@link ConnectionProfile} so the choice persists across launches.
     */
    public void switchActiveDevice(String newDeviceName) {
        if (newDeviceName == null) return;
        String trimmed = newDeviceName.trim();
        if (trimmed.isEmpty() || trimmed.equals(deviceName)) return;
        Log.i(TAG, "Switching active device: " + deviceName + " -> " + trimmed);
        this.deviceName = trimmed;
        this.deviceStatus = "unknown";
        syncActiveProfile(trimmed);
        // Notify status subscribers so the UI flips to "unknown" / "DEVICE OFFLINE"
        // immediately while we wait for the new /status retained message.
        mainHandler.post(() -> {
            for (StatusListener l : statusListeners) {
                try { l.onDeviceStatus("unknown"); } catch (Exception ignored) {}
            }
        });
        if (connected && client != null) {
            disconnect();
            mainHandler.postDelayed(this::connect, 400);
        }
    }

    /**
     * Quietly switch the active device when the configured one is silent at
     * connect time but exactly one other device is online. Avoids the "I
     * connected to MQTT but everything still says offline" trap when the user
     * just reflashed the ESP32 with a new device name. Skips if any of:
     *  - configured device's /status retained message arrived → all good
     *  - 0 or >1 online discovered → ambiguous, let the user pick
     */
    private void maybeAutoPickActive() {
        if (!connected) return;
        String active = deviceName;
        if (active == null || active.isEmpty()) return;
        DiscoveredDevice activeDev = discoveredDevices.get(active);
        if (activeDev != null && activeDev.isOnline()) return;
        DiscoveredDevice onlyOnline = null;
        int onlineCount = 0;
        for (DiscoveredDevice d : discoveredDevices.values()) {
            if (d != null && d.isOnline()) {
                onlineCount++;
                onlyOnline = d;
            }
        }
        if (onlineCount == 1 && onlyOnline != null
                && !active.equals(onlyOnline.deviceName)) {
            Log.i(TAG, "Auto-picking only-online device: "
                    + onlyOnline.deviceName + " (was " + active + ")");
            switchActiveDevice(onlyOnline.deviceName);
        }
    }

    /** Update the {@link ProfileManager} active profile to match the new device. */
    private void syncActiveProfile(String newDeviceName) {
        try {
            com.example.radarhumanapplication.profiles.ProfileManager pm =
                    com.example.radarhumanapplication.profiles.ProfileManager.getInstance();
            for (com.example.radarhumanapplication.profiles.ConnectionProfile p
                    : pm.getProfiles()) {
                if (p == null) continue;
                if (newDeviceName.equals(p.deviceName)
                        && brokerHost != null && brokerHost.equals(p.brokerHost)) {
                    pm.setActive(p.id);
                    return;
                }
            }
            // No saved profile for this (broker, name) combo — drop in a transient
            // one carrying the live MQTT discovery IP so the device picker shows
            // it the next time the user opens it.
            String ip = "";
            DiscoveredDevice d = discoveredDevices.get(newDeviceName);
            if (d != null && d.ip != null) ip = d.ip;
            com.example.radarhumanapplication.profiles.ConnectionProfile np =
                    com.example.radarhumanapplication.profiles.ConnectionProfile.create(
                            newDeviceName, brokerHost, brokerPort,
                            newDeviceName, username, password);
            np.espHttpIp = ip;
            pm.addProfile(np);
            pm.setActive(np.id);
        } catch (Exception e) {
            Log.w(TAG, "syncActiveProfile failed", e);
        }
    }

    /** Bind a long-lived Context (typically the Application) so MQTT can start the foreground
     *  service that keeps it alive in background. Safe to call repeatedly. */
    public void attachContext(Context ctx) {
        if (this.appContext == null && ctx != null) {
            this.appContext = ctx.getApplicationContext();
        }
        if (this.alertPlayer == null && this.appContext != null) {
            this.alertPlayer = new AlertPatternPlayer(this.appContext);
        }
        if (this.deviceStatusAlerts == null && this.appContext != null) {
            this.deviceStatusAlerts = new DeviceStatusAlertManager(this.appContext);
        }
    }

    /** Get the singleton alert pattern player. Must call {@link #attachContext} first. */
    public AlertPatternPlayer getAlertPlayer() { return alertPlayer; }

    /** Get the singleton device-status alert manager. Must call {@link #attachContext} first. */
    public DeviceStatusAlertManager getDeviceStatusAlerts() { return deviceStatusAlerts; }

    /** Mute / unmute live MQTT target fan-out. Called by SessionReplayer so the radar UI
     *  doesn't see live and replayed frames at the same time. */
    public void setLiveTargetMute(boolean muted) { this.liveTargetMuted = muted; }
    public boolean isLiveTargetMuted() { return liveTargetMuted; }

    /** Extra "humanradar/&lt;name&gt;/targets" subscriptions added on top of the active
     *  device's primary subscription. Used by the Radar tab's multi-device
     *  overlay so frames from secondary devices reach the same TargetListener
     *  fan-out, tagged with their _dev so the consumer can route correctly. */
    private final java.util.Set<String> extraTargetSubs =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void subscribeDeviceTargets(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) return;
        if (deviceName.equals(this.deviceName)) return;     // already covered
        if (extraTargetSubs.contains(deviceName)) return;
        extraTargetSubs.add(deviceName);
        if (client == null || !connected) return;
        subscribe("humanradar/" + deviceName + "/targets", this::handleTargets);
    }

    public void unsubscribeDeviceTargets(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) return;
        if (!extraTargetSubs.remove(deviceName)) return;
        if (client == null) return;
        try {
            client.unsubscribeWith()
                    .topicFilter("humanradar/" + deviceName + "/targets")
                    .send();
        } catch (Exception e) {
            Log.w(TAG, "unsubscribeDeviceTargets failed for " + deviceName, e);
        }
    }

    public java.util.Set<String> getExtraTargetSubscriptions() {
        return new java.util.HashSet<>(extraTargetSubs);
    }

    /** Drop a device from the in-memory discovery map. Used by the Dashboard
     *  Hub's Remove action — does NOT unsubscribe wildcards (those keep
     *  catching the device if it ever comes back online). */
    public void removeDiscoveredDevice(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) return;
        discoveredDevices.remove(deviceName);
        mainHandler.post(() -> {
            for (DiscoveryListener l : discoveryListeners) {
                try { l.onDeviceDiscovered(deviceName, "removed",
                        System.currentTimeMillis()); }
                catch (Exception ignored) {}
            }
        });
    }

    /**
     * Public dispatch hook used by {@link com.example.radarhumanapplication.recording.SessionReplayer}
     * to feed replayed frames into the same fan-out as live MQTT. Always runs on the main
     * thread (callers must marshal); does NOT touch {@link #liveTargetMuted} so the caller
     * can choose to mute live and still deliver replay frames.
     */
    public void dispatchTargetsToListeners(JsonObject data) {
        if (data == null) return;
        lastTargetData = data;
        if (alertPlayer != null) {
            AlertPatternConfig acfg = alertPlayer.getConfig();
            int active = AlertPatternPlayer.countActive(data, acfg.maxRangeMm);
            alertPlayer.onTargetCount(active);
        }
        for (TargetListener l : targetListeners) {
            try { l.onTargetsReceived(data); } catch (Exception ignored) {}
        }
    }

    /** Convenience: push new alert config into the player (called by ConfigFragment). */
    public void updateAlertConfig(AlertPatternConfig cfg) {
        if (alertPlayer != null && cfg != null) {
            alertPlayer.setConfig(cfg);
        }
    }

    public void connect() {
        if (brokerHost.isEmpty()) {
            Log.w(TAG, "No broker configured");
            return;
        }

        disconnect();

        // Start foreground service so MQTT stays alive when app goes to background / screen off.
        if (appContext != null) {
            try {
                MqttForegroundService.start(appContext);
            } catch (Exception e) {
                Log.w(TAG, "Failed to start foreground service", e);
            }
        }

        try {
            var builder = MqttClient.builder()
                    .useMqttVersion3()
                    .identifier("HumanRadarApp-" + UUID.randomUUID().toString().substring(0, 8))
                    .serverHost(brokerHost)
                    .serverPort(brokerPort)
                    .automaticReconnectWithDefaultConfig()
                    .addDisconnectedListener(ctx -> {
                        connected = false;
                        mainHandler.post(() -> {
                            for (ConnectionListener l : connectionListeners) {
                                l.onDisconnected(ctx.getCause().getMessage());
                            }
                        });
                    });

            client = builder.buildAsync();

            var connectBuilder = client.connectWith()
                    .cleanSession(true)
                    .keepAlive(30);

            if (!username.isEmpty()) {
                connectBuilder.simpleAuth()
                        .username(username)
                        .password(password.getBytes(StandardCharsets.UTF_8))
                        .applySimpleAuth();
            }

            connectBuilder.send()
                    .whenComplete((connAck, throwable) -> {
                        if (throwable != null) {
                            Log.e(TAG, "Connect failed", throwable);
                            mainHandler.post(() -> {
                                for (ConnectionListener l : connectionListeners) {
                                    l.onDisconnected(throwable.getMessage());
                                }
                            });
                        } else {
                            connected = true;
                            Log.i(TAG, "Connected to " + brokerHost);
                            subscribeAll();
                            mainHandler.post(() -> {
                                for (ConnectionListener l : connectionListeners) {
                                    l.onConnected();
                                }
                            });
                            // Smart auto-pick: 3 s after we connect, if the configured
                            // device is still silent (no /status retained) but EXACTLY
                            // ONE other device is online, switch to it. Multiple online
                            // devices → leave the choice to the user.
                            mainHandler.postDelayed(this::maybeAutoPickActive, 3000);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Connect error", e);
        }
    }

    public void disconnect() {
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Disconnect error", e);
            }
            client = null;
        }
        connected = false;
        discoveredDevices.clear();
        if (appContext != null) {
            try {
                MqttForegroundService.stop(appContext);
            } catch (Exception ignored) {}
        }
    }

    private String topic(String suffix) {
        return "humanradar/" + deviceName + "/" + suffix;
    }

    private void subscribeAll() {
        if (client == null) return;

        subscribe(topic("targets"), this::handleTargets);
        subscribe(topic("status"), this::handleStatus);
        subscribe(topic("config/ack"), this::handleConfigAck);
        subscribe(topic("cmd/ack"), this::handleCmdAck);
        // Logs subscribe to the wildcard so the Logs tab can filter by device
        // without forcing a reconnect each time the user switches view.
        // handleLog extracts the device name from the topic and tags the
        // resulting LogEntry so listeners can route correctly.
        subscribe("humanradar/+/log", this::handleLog);

        // Re-attach any extra-device target subscriptions the Radar tab added
        // before this connection finished (or that survived a reconnect).
        for (String dn : extraTargetSubs) {
            if (dn != null && !dn.isEmpty() && !dn.equals(deviceName)) {
                subscribe("humanradar/" + dn + "/targets", this::handleTargets);
            }
        }

        // Wildcard discovery — picks up every ESP32 that publishes humanradar/<name>/status
        // on the same broker. Used by the device picker to show what's actually online.
        subscribe("humanradar/+/status", this::handleDiscoveryStatus);
        // Retained "info" message carries the device's HTTP IP + firmware version so the
        // picker can show it without the user having to type 192.168.x.x by hand.
        subscribe("humanradar/+/info", this::handleDiscoveryInfo);
    }

    private void handleDiscoveryInfo(Mqtt3Publish publish) {
        try {
            String topicStr = publish.getTopic().toString();
            String[] parts = topicStr.split("/");
            if (parts.length < 3) return;
            String devName = parts[1];
            String body = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            JsonObject obj;
            try {
                obj = gson.fromJson(body, JsonObject.class);
            } catch (Exception parseErr) {
                Log.w(TAG, "info payload not JSON for " + devName + ": " + body);
                return;
            }
            if (obj == null) return;
            String ip  = obj.has("ip")  ? obj.get("ip").getAsString()  : "";
            String fw  = obj.has("fw")  ? obj.get("fw").getAsString()  : "";
            String mac = obj.has("mac") ? obj.get("mac").getAsString() : "";
            long now = System.currentTimeMillis();
            DiscoveredDevice existing = discoveredDevices.get(devName);
            if (existing == null) {
                existing = new DiscoveredDevice(devName, "online", now);
                discoveredDevices.put(devName, existing);
            }
            existing.ip  = ip;
            existing.fw  = fw;
            existing.mac = mac;
            existing.lastSeenMs = now;
            Log.d(TAG, "Discovery info " + devName + " ip=" + ip + " fw=" + fw);
            final DiscoveredDevice forCb = existing;
            mainHandler.post(() -> {
                for (DiscoveryListener l : discoveryListeners) {
                    try { l.onDeviceDiscovered(devName, forCb.status, forCb.lastSeenMs); }
                    catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Discovery info parse failed", e);
        }
    }

    private void handleDiscoveryStatus(Mqtt3Publish publish) {
        try {
            String topicStr = publish.getTopic().toString();
            String[] parts = topicStr.split("/");
            if (parts.length < 3) return;
            String devName = parts[1];
            String status = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8).trim();
            long now = System.currentTimeMillis();
            DiscoveredDevice existing = discoveredDevices.get(devName);
            if (existing == null) {
                discoveredDevices.put(devName, new DiscoveredDevice(devName, status, now));
            } else {
                existing.status = status;
                existing.lastSeenMs = now;
            }
            mainHandler.post(() -> {
                for (DiscoveryListener l : discoveryListeners) {
                    try { l.onDeviceDiscovered(devName, status, now); }
                    catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Discovery status parse failed", e);
        }
    }

    private void subscribe(String topicFilter, java.util.function.Consumer<Mqtt3Publish> handler) {
        client.subscribeWith()
                .topicFilter(topicFilter)
                .callback(handler::accept)
                .send()
                .whenComplete((ack, err) -> {
                    if (err != null) Log.e(TAG, "Sub failed: " + topicFilter, err);
                    else Log.d(TAG, "Subscribed: " + topicFilter);
                });
    }

    // --- Message handlers ---

    private void handleTargets(Mqtt3Publish publish) {
        try {
            String json = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            JsonObject data = gson.fromJson(json, JsonObject.class);
            // Tag every frame with the device name extracted from the MQTT topic so
            // downstream consumers (AlertManager per-device rules, RadarView's
            // device chip, SessionRecorder tagged files) can route correctly
            // without needing a parallel signal path.
            String src = extractDeviceFromTopic(publish.getTopic().toString());
            if (src != null && !src.isEmpty()) data.addProperty("_dev", src);
            lastTargetData = data;
            mainHandler.post(() -> {
                // Feed the alert pattern player (independent of UI listeners)
                if (alertPlayer != null) {
                    AlertPatternConfig acfg = alertPlayer.getConfig();
                    int active = AlertPatternPlayer.countActive(data, acfg.maxRangeMm);
                    alertPlayer.onTargetCount(active);
                }
                // Live target fan-out is muted while a replay is running.
                if (!liveTargetMuted) {
                    for (TargetListener l : targetListeners) l.onTargetsReceived(data);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Parse targets error", e);
        }
    }

    /** humanradar/&lt;name&gt;/targets -&gt; "&lt;name&gt;"; returns "" if the topic format is unexpected. */
    private static String extractDeviceFromTopic(String topic) {
        if (topic == null) return "";
        String[] parts = topic.split("/");
        if (parts.length < 3) return "";
        return parts[1];
    }

    private void handleStatus(Mqtt3Publish publish) {
        String status = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
        deviceStatus = status;
        mainHandler.post(() -> {
            // Fire device online/offline notification sound (per-device, transition-only)
            if (deviceStatusAlerts != null) {
                deviceStatusAlerts.onDeviceStatus(deviceName, status);
            }
            for (StatusListener l : statusListeners) l.onDeviceStatus(status);
        });
    }

    private void handleLog(Mqtt3Publish publish) {
        try {
            String json = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            // Wildcard subscription (humanradar/+/log) carries logs from every
            // device. Pull the source name out of the topic so the Logs tab
            // can group / filter without re-parsing.
            String src = extractDeviceFromTopic(publish.getTopic().toString());
            LogEntry entry = new LogEntry(
                    obj.has("ts") ? obj.get("ts").getAsLong() : 0,
                    obj.has("lvl") ? obj.get("lvl").getAsString() : "?",
                    obj.has("tag") ? obj.get("tag").getAsString() : "",
                    obj.has("msg") ? obj.get("msg").getAsString() : "",
                    obj.has("heap") ? obj.get("heap").getAsLong() : 0,
                    obj.has("up") ? obj.get("up").getAsLong() : 0,
                    src
            );
            logBuffer.add(entry);
            while (logBuffer.size() > MAX_LOG_ENTRIES) {
                logBuffer.remove(0);
            }
            mainHandler.post(() -> {
                for (LogListener l : logListeners) l.onLogReceived(entry);
            });
        } catch (Exception e) {
            Log.e(TAG, "Parse log error", e);
        }
    }

    private void handleConfigAck(Mqtt3Publish publish) {
        try {
            String json = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            JsonObject ack = gson.fromJson(json, JsonObject.class);
            mainHandler.post(() -> {
                for (ConfigAckListener l : configAckListeners) l.onConfigAck(ack);
            });
        } catch (Exception e) {
            Log.e(TAG, "Parse config ack error", e);
        }
    }

    private void handleCmdAck(Mqtt3Publish publish) {
        try {
            String json = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
            JsonObject ack = gson.fromJson(json, JsonObject.class);
            mainHandler.post(() -> {
                for (CmdAckListener l : cmdAckListeners) l.onCmdAck(ack);
            });
        } catch (Exception e) {
            Log.e(TAG, "Parse cmd ack error", e);
        }
    }

    // --- Publish methods ---

    public void sendConfig(JsonObject config) {
        if (!connected || client == null) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("request_id", UUID.randomUUID().toString().substring(0, 8));
        payload.add("config", config);
        publish(topic("config"), payload.toString());
    }

    public void sendCommand(String cmd) {
        sendCommand(cmd, null);
    }

    /**
     * Publish a command targeted at a specific device, not necessarily the
     * currently-active one. Used by the Dashboard Device Hub's per-row actions
     * (Restart / Reboot / ...). Falls back to the active topic if deviceName
     * is empty.
     */
    public void sendCommandTo(String deviceName, String cmd) {
        if (!connected || client == null) return;
        if (deviceName == null || deviceName.isEmpty()) {
            sendCommand(cmd);
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("request_id",
                UUID.randomUUID().toString().substring(0, 8));
        payload.addProperty("cmd", cmd);
        publish("humanradar/" + deviceName + "/cmd", payload.toString());
    }

    public void sendCommand(String cmd, JsonObject extras) {
        if (!connected || client == null) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("request_id", UUID.randomUUID().toString().substring(0, 8));
        payload.addProperty("cmd", cmd);
        if (extras != null) {
            for (String key : extras.keySet()) {
                payload.add(key, extras.get(key));
            }
        }
        publish(topic("cmd"), payload.toString());
    }

    private void publish(String topicStr, String payload) {
        client.publishWith()
                .topic(topicStr)
                .payload(payload.getBytes(StandardCharsets.UTF_8))
                .send()
                .whenComplete((pub, err) -> {
                    if (err != null) Log.e(TAG, "Publish failed: " + topicStr, err);
                });
    }

    public void clearLogBuffer() {
        logBuffer.clear();
    }

    // --- Log Entry data class ---
    public static class LogEntry {
        public final long timestamp;
        public final String level;
        public final String tag;
        public final String message;
        public final long freeHeap;
        public final long uptime;
        /** Source device name extracted from the MQTT topic. "" for legacy
         *  entries created before multi-device log support. */
        public final String device;

        public LogEntry(long timestamp, String level, String tag, String message,
                        long freeHeap, long uptime) {
            this(timestamp, level, tag, message, freeHeap, uptime, "");
        }

        public LogEntry(long timestamp, String level, String tag, String message,
                        long freeHeap, long uptime, String device) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.freeHeap = freeHeap;
            this.uptime = uptime;
            this.device = device == null ? "" : device;
        }
    }
}
