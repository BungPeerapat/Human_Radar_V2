#include "mqtt_client.h"
#include "logger.h"
#include "power_monitor.h"

MqttRadarClient mqttClient;

// Static instance pointer for callback routing
static MqttRadarClient* _mqttInstance = nullptr;

// ============================================================================
// WSMqttClient — WebSocket transport (unchanged from before)
// ============================================================================
WSMqttClient* WSMqttClient::_self = nullptr;

void WSMqttClient::configure(bool useTLS, const char* path) {
    _useTLS = useTLS;
    _path = path;
}

void WSMqttClient::onEvent(WStype_t type, uint8_t* payload, size_t length) {
    if (!_self) return;
    switch (type) {
    case WStype_CONNECTED:  _self->_connected = true; break;
    case WStype_DISCONNECTED:
        _self->_connected = false;
        _self->_head = _self->_tail = 0;
        break;
    case WStype_BIN: _self->ringPush(payload, length); break;
    default: break;
    }
}

size_t WSMqttClient::ringAvailable() const {
    if (_head >= _tail) return _head - _tail;
    return MQTT_WS_RINGBUF_SIZE - _tail + _head;
}

void WSMqttClient::ringPush(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        size_t next = (_head + 1) % MQTT_WS_RINGBUF_SIZE;
        if (next == _tail) break;
        _ring[_head] = data[i];
        _head = next;
    }
}

int WSMqttClient::connect(IPAddress ip, uint16_t port) {
    return connect(ip.toString().c_str(), port);
}

int WSMqttClient::connect(const char* host, uint16_t port) {
    _self = this;
    _connected = false;
    _head = _tail = 0;
    _ws.onEvent(onEvent);
    _ws.setReconnectInterval(0);
    _ws.enableHeartbeat(15000, 3000, 2);
    if (_useTLS) _ws.beginSSL(host, port, _path, "", "mqtt");
    else         _ws.begin(host, port, _path, "mqtt");
    uint32_t start = millis();
    while (!_connected && (millis() - start) < MQTT_WS_CONNECT_TIMEOUT) {
        _ws.loop();
        delay(10);
    }
    return _connected ? 1 : 0;
}

size_t WSMqttClient::write(uint8_t b) { return write(&b, 1); }
size_t WSMqttClient::write(const uint8_t* buf, size_t size) {
    if (!_connected) return 0;
    _ws.sendBIN(buf, size);
    return size;
}

int WSMqttClient::available() { _ws.loop(); return ringAvailable(); }
int WSMqttClient::read() {
    if (_head == _tail) return -1;
    uint8_t b = _ring[_tail];
    _tail = (_tail + 1) % MQTT_WS_RINGBUF_SIZE;
    return b;
}
int WSMqttClient::read(uint8_t* buf, size_t size) {
    size_t count = 0;
    while (count < size && _head != _tail) {
        buf[count++] = _ring[_tail];
        _tail = (_tail + 1) % MQTT_WS_RINGBUF_SIZE;
    }
    return count;
}
int WSMqttClient::peek() { return (_head == _tail) ? -1 : _ring[_tail]; }
void WSMqttClient::stop() { _ws.disconnect(); _connected = false; _head = _tail = 0; }
uint8_t WSMqttClient::connected() { return _connected ? 1 : 0; }
WSMqttClient::operator bool() { return _connected; }

// ============================================================================
// MqttRadarClient — Core
// ============================================================================

bool MqttRadarClient::isEnabled() const {
    const DeviceConfig& cfg = configManager.get();
    return cfg.mqttEnabled && strlen(cfg.mqttHost) > 0;
}

const char* MqttRadarClient::getStatusText() {
    if (!isEnabled()) return "disabled";
    if (_mqtt.connected()) return "connected";
    return "disconnected";
}

static const char* PROTO_NAMES[] = {"ws", "wss", "mqtt/tcp", "mqtts/tls"};

const char* MqttRadarClient::getProtoText() const {
    return PROTO_NAMES[_proto & 3];
}

void MqttRadarClient::setupTransport() {
    const DeviceConfig& cfg = configManager.get();
    _proto = cfg.mqttProto & 3;
    switch (_proto) {
    case MQTT_PROTO_WS:
        _wsClient.configure(false, "/mqtt");
        _mqtt.setClient(_wsClient);
        break;
    case MQTT_PROTO_WSS:
        _wsClient.configure(true, "/mqtt");
        _mqtt.setClient(_wsClient);
        break;
    case MQTT_PROTO_TCP:
        _mqtt.setClient(_tcpClient);
        break;
    case MQTT_PROTO_TLS:
        _tlsClient.setInsecure();
        _mqtt.setClient(_tlsClient);
        break;
    }
    Log::info(TAG_MQTT, "Transport: %s", getProtoText());
}

void MqttRadarClient::buildTopics() {
    const DeviceConfig& cfg = configManager.get();
    const char* name = strlen(cfg.deviceName) > 0 ? cfg.deviceName : "HumanRadar";
    snprintf(_topicTargets,   sizeof(_topicTargets),   "humanradar/%s/targets",    name);
    snprintf(_topicStatus,    sizeof(_topicStatus),    "humanradar/%s/status",     name);
    snprintf(_topicInfo,      sizeof(_topicInfo),      "humanradar/%s/info",       name);
    snprintf(_topicLog,       sizeof(_topicLog),       "humanradar/%s/log",        name);
    snprintf(_topicConfig,    sizeof(_topicConfig),    "humanradar/%s/config",     name);
    snprintf(_topicConfigAck, sizeof(_topicConfigAck), "humanradar/%s/config/ack", name);
    snprintf(_topicCmd,       sizeof(_topicCmd),       "humanradar/%s/cmd",        name);
    snprintf(_topicCmdAck,    sizeof(_topicCmdAck),    "humanradar/%s/cmd/ack",    name);
}

// Publish a retained discovery message so the app's device picker can find
// the ESP32's HTTP IP without the user having to type 192.168.x.x manually.
// Payload is {"ip":"…","fw":"…","name":"…","mac":"…"}.
void MqttRadarClient::publishInfo() {
    const DeviceConfig& cfg = configManager.get();
    String ip = WiFi.getMode() == WIFI_AP
            ? WiFi.softAPIP().toString()
            : WiFi.localIP().toString();
    char payload[256];
    int n = snprintf(payload, sizeof(payload),
                     "{\"ip\":\"%s\",\"fw\":\"%s\",\"name\":\"%s\",\"mac\":\"%s\"}",
                     ip.c_str(),
                     FW_VERSION,
                     strlen(cfg.deviceName) > 0 ? cfg.deviceName : "HumanRadar",
                     WiFi.macAddress().c_str());
    (void)n;
    _mqtt.publish(_topicInfo, (const uint8_t*)payload, strlen(payload), true);
    Log::info(TAG_MQTT, "Published info: %s", payload);
}

// ============================================================================
// Begin
// ============================================================================
void MqttRadarClient::begin() {
    _mqttInstance = this;

    if (!isEnabled()) {
        Log::info(TAG_MQTT, "MQTT disabled");
        return;
    }

    const DeviceConfig& cfg = configManager.get();
    setupTransport();
    _mqtt.setServer(cfg.mqttHost, cfg.mqttPort);
    _mqtt.setBufferSize(MQTT_BUFFER_SIZE);
    _mqtt.setCallback(mqttCallback);

    buildTopics();

    Log::info(TAG_MQTT, "Broker: %s:%d (%s)", cfg.mqttHost, cfg.mqttPort, getProtoText());
    Log::info(TAG_MQTT, "Pub: %s", _topicTargets);
    Log::info(TAG_MQTT, "Sub: %s, %s", _topicConfig, _topicCmd);

    _initialized = true;

    // Register MQTT log publisher
    Log::setMqttPublisher([](const LogEntry& entry) {
        mqttClient.publishLog(entry);
    });

    tryConnect();
}

// ============================================================================
// Connect + Subscribe
// ============================================================================
bool MqttRadarClient::tryConnect() {
    const DeviceConfig& cfg = configManager.get();
    const char* clientId = cfg.deviceName;

    Log::info(TAG_MQTT, "Connecting as '%s' via %s...", clientId, getProtoText());

    bool ok;
    if (strlen(cfg.mqttUser) > 0) {
        ok = _mqtt.connect(clientId, cfg.mqttUser, cfg.mqttPass,
                           _topicStatus, 1, true, "offline");
    } else {
        ok = _mqtt.connect(clientId, nullptr, nullptr,
                           _topicStatus, 1, true, "offline");
    }

    if (ok) {
        Log::info(TAG_MQTT, "Connected! (%s)", getProtoText());
        _mqtt.publish(_topicStatus, "online", true);
        publishInfo();
        subscribeAll();
        _wasConnected = true;

        // Enable MQTT log forwarding now that we're connected
        Log::enableMqttForward(true);
    } else {
        Log::error(TAG_MQTT, "Connect failed (rc=%d)", _mqtt.state());
    }

    return ok;
}

void MqttRadarClient::subscribeAll() {
    _mqtt.subscribe(_topicConfig, 1);
    _mqtt.subscribe(_topicCmd, 1);
    Log::info(TAG_MQTT, "Subscribed to config + cmd topics");
}

// ============================================================================
// MQTT Callback (static → instance)
// ============================================================================
void MqttRadarClient::mqttCallback(char* topic, byte* payload, unsigned int length) {
    if (_mqttInstance) {
        _mqttInstance->handleMessage(topic, payload, length);
    }
}

void MqttRadarClient::handleMessage(const char* topic, const uint8_t* payload, unsigned int length) {
    // CRITICAL: Copy payload before any MQTT publish (including Log forwarding),
    // because PubSubClient reuses its internal buffer for both send and receive.
    if (length > MQTT_BUFFER_SIZE) {
        Log::error(TAG_MQTT, "Message too large: %d bytes", length);
        return;
    }
    uint8_t* buf = (uint8_t*)malloc(length + 1);
    if (!buf) {
        Log::error(TAG_MQTT, "OOM copying payload");
        return;
    }
    memcpy(buf, payload, length);
    buf[length] = 0;

    if (strcmp(topic, _topicConfig) == 0) {
        Log::info(TAG_MQTT, "Config received (%d bytes)", length);
        handleConfig(buf, length);
    } else if (strcmp(topic, _topicCmd) == 0) {
        Log::info(TAG_MQTT, "Command received (%d bytes)", length);
        handleCommand(buf, length);
    }

    free(buf);
}

// ============================================================================
// Config Handler — parse JSON, apply to NVS, publish ACK
// ============================================================================
void MqttRadarClient::handleConfig(const uint8_t* payload, unsigned int length) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, payload, length);
    if (err) {
        Log::error(TAG_MQTT, "Config JSON parse error: %s", err.c_str());
        return;
    }

    const char* requestId = doc["request_id"] | "";
    JsonObject config = doc["config"];
    if (config.isNull()) {
        Log::error(TAG_MQTT, "Config: missing 'config' object");
        return;
    }

    // Track applied and rejected fields
    JsonDocument ackDoc;
    ackDoc["request_id"] = requestId;
    JsonObject applied = ackDoc["applied"].to<JsonObject>();
    JsonArray rejected = ackDoc["rejected"].to<JsonArray>();

    // --- device_name ---
    if (config.containsKey("device_name")) {
        const char* name = config["device_name"];
        if (name && strlen(name) > 0 && strlen(name) <= 32) {
            configManager.setDeviceName(name);
            applied["device_name"] = name;
        } else {
            JsonObject r = rejected.add<JsonObject>();
            r["field"] = "device_name";
            r["reason"] = "empty or too long (max 32)";
        }
    }

    // --- publish_interval_ms ---
    if (config.containsKey("publish_interval_ms")) {
        int val = config["publish_interval_ms"];
        if (val >= 50 && val <= 2000) {
            configManager.setPublishInterval(val);
            applied["publish_interval_ms"] = val;
        } else {
            JsonObject r = rejected.add<JsonObject>();
            r["field"] = "publish_interval_ms";
            r["reason"] = "out of range 50-2000";
        }
    }

    // --- unmanned_delay_ms ---
    if (config.containsKey("unmanned_delay_ms")) {
        int val = config["unmanned_delay_ms"];
        if (val >= 1000 && val <= 60000) {
            configManager.setUnmannedDelay(val);
            applied["unmanned_delay_ms"] = val;
        } else {
            JsonObject r = rejected.add<JsonObject>();
            r["field"] = "unmanned_delay_ms";
            r["reason"] = "out of range 1000-60000";
        }
    }

    // --- sensitivity ---
    if (config.containsKey("sensitivity")) {
        int val = config["sensitivity"];
        if (val >= 0 && val <= 9) {
            configManager.setSensitivity(val);
            applied["sensitivity"] = val;
        } else {
            JsonObject r = rejected.add<JsonObject>();
            r["field"] = "sensitivity";
            r["reason"] = "out of range 0-9";
        }
    }

    // --- target_timeout_ms ---
    if (config.containsKey("target_timeout_ms")) {
        int val = config["target_timeout_ms"];
        if (val >= 100 && val <= 10000) {
            configManager.setTargetTimeout(val);
            applied["target_timeout_ms"] = val;
        } else {
            JsonObject r = rejected.add<JsonObject>();
            r["field"] = "target_timeout_ms";
            r["reason"] = "out of range 100-10000";
        }
    }

    // --- multi_target_mode ---
    if (config.containsKey("multi_target_mode")) {
        bool val = config["multi_target_mode"];
        configManager.setMultiTargetMode(val ? 1 : 0);
        applied["multi_target_mode"] = val;
    }

    // --- detection_zones ---
    if (config.containsKey("detection_zones")) {
        JsonArray zones = config["detection_zones"];
        for (JsonObject z : zones) {
            int zoneId = z["zone_id"] | -1;
            if (zoneId < 1 || zoneId > 3) {
                JsonObject r = rejected.add<JsonObject>();
                r["field"] = "detection_zones";
                r["reason"] = "zone_id must be 1-3";
                continue;
            }
            bool enabled = z["enabled"] | false;
            int16_t x1 = z["x1"] | 0;
            int16_t y1 = z["y1"] | 0;
            int16_t x2 = z["x2"] | 0;
            int16_t y2 = z["y2"] | 0;
            configManager.setZone(zoneId - 1, enabled, x1, y1, x2, y2);
        }
        applied["detection_zones"] = true;
    }

    // Build ACK
    bool hasRejected = rejected.size() > 0;
    ackDoc["status"] = hasRejected ? (applied.size() > 0 ? "partial" : "error") : "ok";
    ackDoc["firmware_version"] = FW_VERSION;
    ackDoc["timestamp"] = millis() / 1000;

    // Publish ACK
    char ackBuf[MQTT_BUFFER_SIZE];
    size_t ackLen = serializeJson(ackDoc, ackBuf, sizeof(ackBuf));
    _mqtt.publish(_topicConfigAck, (const uint8_t*)ackBuf, ackLen, false);

    Log::info(TAG_MQTT, "Config ACK sent: status=%s", ackDoc["status"].as<const char*>());
}

// ============================================================================
// Command Handler
// ============================================================================
void MqttRadarClient::handleCommand(const uint8_t* payload, unsigned int length) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, payload, length);
    if (err) {
        Log::error(TAG_MQTT, "Cmd JSON parse error: %s", err.c_str());
        return;
    }

    const char* cmd = doc["cmd"] | "";
    const char* requestId = doc["request_id"] | "";

    if (strcmp(cmd, "restart") == 0) {
        cmdRestart(requestId);
    } else if (strcmp(cmd, "factory_reset") == 0) {
        cmdFactoryReset(requestId);
    } else if (strcmp(cmd, "get_log_buffer") == 0) {
        int limit = doc["limit"] | 100;
        cmdGetLogBuffer(requestId, limit);
    } else if (strcmp(cmd, "set_log_level") == 0) {
        const char* level = doc["level"] | "INFO";
        cmdSetLogLevel(requestId, level);
    } else if (strcmp(cmd, "health") == 0) {
        cmdHealth(requestId);
    } else {
        Log::warn(TAG_MQTT, "Unknown command: %s", cmd);
        // Publish error ACK
        char ack[256];
        snprintf(ack, sizeof(ack),
            "{\"request_id\":\"%s\",\"status\":\"error\",\"message\":\"unknown command: %s\"}",
            requestId, cmd);
        _mqtt.publish(_topicCmdAck, ack);
    }
}

void MqttRadarClient::cmdRestart(const char* requestId) {
    Log::info(TAG_MQTT, "CMD: restart");
    char ack[192];
    snprintf(ack, sizeof(ack),
        "{\"request_id\":\"%s\",\"status\":\"ok\",\"message\":\"restarting in 2s\"}", requestId);
    _mqtt.publish(_topicCmdAck, ack);
    delay(2000);
    ESP.restart();
}

void MqttRadarClient::cmdFactoryReset(const char* requestId) {
    Log::info(TAG_MQTT, "CMD: factory_reset");
    configManager.resetToDefaults();
    PowerMonitor::resetCounters();
    char ack[192];
    snprintf(ack, sizeof(ack),
        "{\"request_id\":\"%s\",\"status\":\"ok\",\"message\":\"reset done, restarting\"}", requestId);
    _mqtt.publish(_topicCmdAck, ack);
    delay(2000);
    ESP.restart();
}

void MqttRadarClient::cmdGetLogBuffer(const char* requestId, int limit) {
    Log::info(TAG_MQTT, "CMD: get_log_buffer (limit=%d)", limit);

    uint16_t count = Log::getBufferCount();
    uint16_t start = (count > limit) ? count - limit : 0;

    // Send logs one by one (each as a separate MQTT message on log topic)
    for (uint16_t i = start; i < count; i++) {
        const LogEntry& e = Log::getBufferEntry(i);
        publishLog(e);
        yield();  // prevent WDT during large buffer sends
    }

    char ack[192];
    snprintf(ack, sizeof(ack),
        "{\"request_id\":\"%s\",\"status\":\"ok\",\"sent\":%d}", requestId, count - start);
    _mqtt.publish(_topicCmdAck, ack);
}

// Lightweight "is this device alive?" probe. The app's HealthCheckManager
// sends this to a known device and watches `humanradar/+/status`,
// `humanradar/+/info` and `cmd/ack` for any of those three to arrive before
// the deadline. Devices that don't respond are considered offline and the
// app sweeps their stale targets off the radar canvas.
void MqttRadarClient::cmdHealth(const char* requestId) {
    Log::info(TAG_MQTT, "CMD: health");
    // Re-assert liveness on the wildcard-watched topics first — that's what
    // the app actually keys off for the "did this device respond" check.
    _mqtt.publish(_topicStatus, "online", true);
    publishInfo();

    const DeviceConfig& cfg = configManager.get();
    String ip = WiFi.getMode() == WIFI_AP
            ? WiFi.softAPIP().toString()
            : WiFi.localIP().toString();
    long rssi = (WiFi.getMode() == WIFI_AP) ? 0 : WiFi.RSSI();
    PowerMonitor::Snapshot pwr = PowerMonitor::snapshot();
    // Die temp can be NaN on some chips — emit -1 sentinel so JSON stays valid.
    float tempC = isnan(pwr.dieTempC) ? -1.0f : pwr.dieTempC;

    char ack[512];
    snprintf(ack, sizeof(ack),
        "{\"request_id\":\"%s\",\"status\":\"ok\",\"cmd\":\"health\","
        "\"device\":\"%s\",\"fw\":\"%s\",\"ip\":\"%s\","
        "\"uptime\":%lu,\"heap\":%lu,\"rssi\":%ld,"
        "\"reset\":%u,\"bo\":%lu,\"panic\":%lu,\"boots\":%lu,"
        "\"temp\":%.1f,\"pwr_score\":%u,\"pwr_lvl\":%u}",
        requestId,
        strlen(cfg.deviceName) > 0 ? cfg.deviceName : "HumanRadar",
        FW_VERSION,
        ip.c_str(),
        (unsigned long)(millis() / 1000UL),
        (unsigned long)ESP.getFreeHeap(),
        rssi,
        (unsigned)pwr.resetReason,
        (unsigned long)pwr.brownoutCountTotal,
        (unsigned long)pwr.panicCountTotal,
        (unsigned long)pwr.bootCount,
        tempC,
        (unsigned)pwr.healthScore,
        (unsigned)pwr.healthLevel);
    _mqtt.publish(_topicCmdAck, ack);
}

void MqttRadarClient::cmdSetLogLevel(const char* requestId, const char* level) {
    LogLevel newLevel = LOG_INFO;
    if (strcmp(level, "DEBUG") == 0)      newLevel = LOG_DEBUG;
    else if (strcmp(level, "INFO") == 0)  newLevel = LOG_INFO;
    else if (strcmp(level, "WARN") == 0)  newLevel = LOG_WARN;
    else if (strcmp(level, "ERROR") == 0) newLevel = LOG_ERROR;
    else if (strcmp(level, "RADAR") == 0) newLevel = LOG_RADAR;

    Log::setMqttMinLevel(newLevel);
    Log::info(TAG_MQTT, "MQTT log level set to: %s", level);

    char ack[192];
    snprintf(ack, sizeof(ack),
        "{\"request_id\":\"%s\",\"status\":\"ok\",\"level\":\"%s\"}", requestId, level);
    _mqtt.publish(_topicCmdAck, ack);
}

// ============================================================================
// Loop
// ============================================================================
void MqttRadarClient::loop() {
    if (!isEnabled() || !_initialized) return;

    if (_proto == MQTT_PROTO_WS || _proto == MQTT_PROTO_WSS) {
        _wsClient.wsLoop();
    }

    if (_mqtt.connected()) {
        _mqtt.loop();
        return;
    }

    // Lost connection — disable MQTT log forwarding to avoid recursion
    if (_wasConnected) {
        Log::enableMqttForward(false);
        Log::error(TAG_MQTT, "Connection lost (%s), reconnecting...", getProtoText());
        _wasConnected = false;
    }

    uint32_t now = millis();
    if (now - _lastReconnectAttempt >= MQTT_RECONNECT_INTERVAL) {
        _lastReconnectAttempt = now;
        tryConnect();
    }
}

// ============================================================================
// Publish Radar Frame
// ============================================================================
void MqttRadarClient::publishFrame(const RadarFrame& frame, uint32_t frameCount, uint32_t errorCount) {
    if (!_mqtt.connected()) return;

    // Use runtime publish interval from config
    uint32_t interval = configManager.get().publishIntervalMs;
    uint32_t now = millis();
    if (now - _lastPublish < interval) return;
    _lastPublish = now;

    char json[512];
    int len = snprintf(json, sizeof(json),
        "{\"t\":["
        "{\"x\":%d,\"y\":%d,\"s\":%d,\"d\":%u,\"a\":%.1f,\"p\":%s},"
        "{\"x\":%d,\"y\":%d,\"s\":%d,\"d\":%u,\"a\":%.1f,\"p\":%s},"
        "{\"x\":%d,\"y\":%d,\"s\":%d,\"d\":%u,\"a\":%.1f,\"p\":%s}"
        "],\"fc\":%lu,\"ec\":%lu}",
        frame.targets[0].x, frame.targets[0].y, frame.targets[0].speed,
        frame.targets[0].distance, frame.targets[0].angle,
        frame.targets[0].present ? "true" : "false",
        frame.targets[1].x, frame.targets[1].y, frame.targets[1].speed,
        frame.targets[1].distance, frame.targets[1].angle,
        frame.targets[1].present ? "true" : "false",
        frame.targets[2].x, frame.targets[2].y, frame.targets[2].speed,
        frame.targets[2].distance, frame.targets[2].angle,
        frame.targets[2].present ? "true" : "false",
        frameCount, errorCount
    );

    _mqtt.publish(_topicTargets, (const uint8_t*)json, len, false);
    _publishCount++;
}

// ============================================================================
// Publish Log Entry
// ============================================================================
void MqttRadarClient::publishLog(const LogEntry& entry) {
    if (!_mqtt.connected()) return;

    const char* lvl = (entry.level < LOG_LEVEL_COUNT) ? LOG_LEVEL_NAMES[entry.level] : "?";

    char json[384];
    int len = snprintf(json, sizeof(json),
        "{\"ts\":%lu,\"lvl\":\"%s\",\"tag\":\"%s\",\"msg\":\"%s\",\"heap\":%lu,\"up\":%lu}",
        entry.timestamp, lvl, entry.tag, entry.message, entry.freeHeap, entry.uptime);

    _mqtt.publish(_topicLog, (const uint8_t*)json, len, false);
}
