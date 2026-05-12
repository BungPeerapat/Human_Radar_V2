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
import java.util.List;
import java.util.UUID;
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
    private boolean connected = false;
    private Context appContext;
    private AlertPatternPlayer alertPlayer;
    private DeviceStatusAlertManager deviceStatusAlerts;

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

    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final List<TargetListener> targetListeners = new CopyOnWriteArrayList<>();
    private final List<StatusListener> statusListeners = new CopyOnWriteArrayList<>();
    private final List<LogListener> logListeners = new CopyOnWriteArrayList<>();
    private final List<ConfigAckListener> configAckListeners = new CopyOnWriteArrayList<>();
    private final List<CmdAckListener> cmdAckListeners = new CopyOnWriteArrayList<>();

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
        subscribe(topic("log"), this::handleLog);
        subscribe(topic("config/ack"), this::handleConfigAck);
        subscribe(topic("cmd/ack"), this::handleCmdAck);
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
            lastTargetData = data;
            mainHandler.post(() -> {
                // Feed the alert pattern player (independent of UI listeners)
                if (alertPlayer != null) {
                    AlertPatternConfig acfg = alertPlayer.getConfig();
                    int active = AlertPatternPlayer.countActive(data, acfg.maxRangeMm);
                    alertPlayer.onTargetCount(active);
                }
                for (TargetListener l : targetListeners) l.onTargetsReceived(data);
            });
        } catch (Exception e) {
            Log.e(TAG, "Parse targets error", e);
        }
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
            LogEntry entry = new LogEntry(
                    obj.has("ts") ? obj.get("ts").getAsLong() : 0,
                    obj.has("lvl") ? obj.get("lvl").getAsString() : "?",
                    obj.has("tag") ? obj.get("tag").getAsString() : "",
                    obj.has("msg") ? obj.get("msg").getAsString() : "",
                    obj.has("heap") ? obj.get("heap").getAsLong() : 0,
                    obj.has("up") ? obj.get("up").getAsLong() : 0
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

        public LogEntry(long timestamp, String level, String tag, String message, long freeHeap, long uptime) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.freeHeap = freeHeap;
            this.uptime = uptime;
        }
    }
}
