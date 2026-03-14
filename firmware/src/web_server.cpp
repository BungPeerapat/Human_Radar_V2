#include "web_server.h"
#include "mqtt_client.h"
#include "logger.h"

// Global instance
WebRadarServer webServer;

// Static callback needs access to instance
static WebRadarServer* _instance = nullptr;

void WebRadarServer::begin() {
    _instance = this;
    setupWiFi();
    setupMDNS();
    setupHTTP();
    setupWebSocket();

    // Start captive portal DNS in AP mode
    if (_isAP) {
        _dns.start(53, "*", WiFi.softAPIP());
        Log::info("Captive portal DNS started");
    }

    _ready = true;
}

void WebRadarServer::loop() {
    if (_isAP) _dns.processNextRequest();
    _ws.loop();
    _http.handleClient();
}

// ============================================================================
// WiFi Setup - uses ConfigManager for stored credentials
// ============================================================================
void WebRadarServer::setupWiFi() {
    const DeviceConfig& cfg = configManager.get();

    if (cfg.wifiMode == 1 && strlen(cfg.wifiSSID) > 0) {
        // Station mode - connect to user's WiFi
        WiFi.mode(WIFI_STA);
        WiFi.setHostname(cfg.deviceName);
        WiFi.begin(cfg.wifiSSID, cfg.wifiPass);
        Log::info("Connecting to WiFi: %s", cfg.wifiSSID);

        uint32_t startMs = millis();
        while (WiFi.status() != WL_CONNECTED) {
            delay(500);
            Serial.print(".");
            if (millis() - startMs > WIFI_STA_TIMEOUT) {
                Serial.println();
                Log::error("WiFi timeout! Falling back to AP mode.");
                // Fall through to AP mode below
                goto start_ap;
            }
        }
        Serial.println();
        _ip = WiFi.localIP().toString();
        Log::info("WiFi connected! IP: %s", _ip.c_str());
        return;
    }

start_ap:
    // Access Point mode (default or fallback)
    WiFi.mode(WIFI_AP);
    WiFi.softAP(WIFI_AP_SSID, WIFI_AP_PASS, WIFI_AP_CHANNEL, 0, WIFI_AP_MAX_CONN);
    delay(100);
    _isAP = true;
    _ip = WiFi.softAPIP().toString();
    Log::info("WiFi AP started: %s (pass: %s)", WIFI_AP_SSID, WIFI_AP_PASS);
    Log::info("IP: %s", _ip.c_str());
}

// ============================================================================
// mDNS - access via http://humanradar.local
// ============================================================================
void WebRadarServer::setupMDNS() {
    const DeviceConfig& cfg = configManager.get();
    // Use device name as mDNS hostname, fallback to "humanradar"
    String hostname = String(cfg.deviceName);
    hostname.toLowerCase();
    hostname.replace(" ", "");

    if (MDNS.begin(hostname.c_str())) {
        MDNS.addService("http", "tcp", WEB_SERVER_PORT);
        Log::info("mDNS: http://%s.local", hostname.c_str());
    } else {
        Log::error("mDNS failed to start");
    }
}

// ============================================================================
// HTTP Server
// ============================================================================
void WebRadarServer::setupHTTP() {
    // Radar page
    _http.on("/", HTTP_GET, [this]() {
        _http.send_P(200, "text/html", RADAR_HTML);
    });

    // Settings page
    _http.on("/settings", HTTP_GET, [this]() {
        _http.send_P(200, "text/html", SETTINGS_HTML);
    });

    // API: Get config
    _http.on("/api/config", HTTP_GET, [this]() {
        handleGetConfig();
    });

    // API: Save config
    _http.on("/api/config", HTTP_POST, [this]() {
        handleSaveConfig();
    });

    // API: Reset to defaults
    _http.on("/api/reset", HTTP_POST, [this]() {
        handleResetConfig();
    });

    // Health check
    _http.on("/health", HTTP_GET, [this]() {
        _http.send(200, "application/json", "{\"status\":\"ok\"}");
    });

    // Captive portal: redirect unknown URLs to /settings in AP mode
    _http.onNotFound([this]() {
        if (_isAP) {
            _http.sendHeader("Location", "http://" + _ip + "/settings", true);
            _http.send(302, "text/plain", "Redirecting to settings...");
        } else {
            _http.send(404, "text/plain", "Not found");
        }
    });

    _http.begin();
    Log::info("HTTP server on port %d", WEB_SERVER_PORT);
}

// ============================================================================
// API: Get Config (JSON)
// ============================================================================
void WebRadarServer::handleGetConfig() {
    const DeviceConfig& cfg = configManager.get();

    char json[1024];
    int len = snprintf(json, sizeof(json),
        "{\"wm\":%d,\"ws\":\"%s\",\"wp\":\"%s\","
        "\"me\":%d,\"mr\":%d,\"mh\":\"%s\",\"mp\":%d,\"mu\":\"%s\",\"mpp\":\"%s\",\"ms\":\"%s\","
        "\"dn\":\"%s\",\"ip\":\"%s\","
        "\"pi\":%d,\"ud\":%d,\"tt\":%d,\"mt\":%d,\"sn\":%d,"
        "\"z0\":{\"en\":%d,\"x1\":%d,\"y1\":%d,\"x2\":%d,\"y2\":%d},"
        "\"z1\":{\"en\":%d,\"x1\":%d,\"y1\":%d,\"x2\":%d,\"y2\":%d},"
        "\"z2\":{\"en\":%d,\"x1\":%d,\"y1\":%d,\"x2\":%d,\"y2\":%d},"
        "\"fw\":\"%s\"}",
        cfg.wifiMode, cfg.wifiSSID, cfg.wifiPass,
        cfg.mqttEnabled, cfg.mqttProto,
        cfg.mqttHost, cfg.mqttPort, cfg.mqttUser, cfg.mqttPass,
        mqttClient.getStatusText(),
        cfg.deviceName, _ip.c_str(),
        cfg.publishIntervalMs, cfg.unmannedDelayMs, cfg.targetTimeoutMs,
        cfg.multiTargetMode, cfg.sensitivity,
        cfg.zones[0].enabled, cfg.zones[0].x1, cfg.zones[0].y1, cfg.zones[0].x2, cfg.zones[0].y2,
        cfg.zones[1].enabled, cfg.zones[1].x1, cfg.zones[1].y1, cfg.zones[1].x2, cfg.zones[1].y2,
        cfg.zones[2].enabled, cfg.zones[2].x1, cfg.zones[2].y1, cfg.zones[2].x2, cfg.zones[2].y2,
        FW_VERSION
    );

    _http.send(200, "application/json", json);
}

// ============================================================================
// API: Save Config (JSON POST)
// ============================================================================
void WebRadarServer::handleSaveConfig() {
    if (!_http.hasArg("plain")) {
        _http.send(400, "application/json", "{\"ok\":false,\"error\":\"No body\"}");
        return;
    }

    String body = _http.arg("plain");
    Log::info("Save config: %s", body.c_str());

    // Simple JSON parsing (no library needed for flat structure)
    // Extract values using indexOf/substring
    auto getJsonStr = [&](const char* key) -> String {
        String search = String("\"") + key + "\":\"";
        int start = body.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = body.indexOf("\"", start);
        if (end < 0) return "";
        return body.substring(start, end);
    };

    auto getJsonInt = [&](const char* key, int def) -> int {
        String search = String("\"") + key + "\":";
        int start = body.indexOf(search);
        if (start < 0) return def;
        start += search.length();
        return body.substring(start).toInt();
    };

    // Save WiFi
    uint8_t wm = (uint8_t)getJsonInt("wm", 0);
    String ws = getJsonStr("ws");
    String wp = getJsonStr("wp");
    configManager.setWiFi(wm, ws.c_str(), wp.c_str());

    // Save MQTT
    uint8_t me = (uint8_t)getJsonInt("me", 0);
    uint8_t mr = (uint8_t)getJsonInt("mr", 2);
    String mh = getJsonStr("mh");
    int mp = getJsonInt("mp", 1883);
    String mu = getJsonStr("mu");
    String mpp = getJsonStr("mpp");
    configManager.setMQTT(me, mr, mh.c_str(), (uint16_t)mp, mu.c_str(), mpp.c_str());

    // Save device name
    String dn = getJsonStr("dn");
    if (dn.length() > 0) {
        configManager.setDeviceName(dn.c_str());
    }

    // Save sensor config
    int pi = getJsonInt("pi", -1);
    if (pi > 0) configManager.setPublishInterval((uint16_t)pi);

    int ud = getJsonInt("ud", -1);
    if (ud > 0) configManager.setUnmannedDelay((uint16_t)ud);

    int tt = getJsonInt("tt", -1);
    if (tt > 0) configManager.setTargetTimeout((uint16_t)tt);

    int mt = getJsonInt("mt", -1);
    if (mt >= 0) configManager.setMultiTargetMode((uint8_t)mt);

    int sn = getJsonInt("sn", -1);
    if (sn >= 0) configManager.setSensitivity((uint8_t)sn);

    _http.send(200, "application/json", "{\"ok\":true}");

    // Restart after short delay to apply new WiFi settings
    Log::info("Restarting in 2 seconds to apply settings...");
    delay(2000);
    ESP.restart();
}

// ============================================================================
// API: Reset to Defaults
// ============================================================================
void WebRadarServer::handleResetConfig() {
    configManager.resetToDefaults();
    _http.send(200, "application/json", "{\"ok\":true}");

    Log::info("Restarting in 2 seconds...");
    delay(2000);
    ESP.restart();
}

// ============================================================================
// WebSocket Server
// ============================================================================
void WebRadarServer::setupWebSocket() {
    _ws.begin();
    _ws.onEvent(onWebSocketEvent);
    Log::info("WebSocket server on port %d", WEBSOCKET_PORT);
}

void WebRadarServer::onWebSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
    if (!_instance) return;

    switch (type) {
    case WStype_CONNECTED:
        _instance->_clientCount++;
        Log::info("WS client #%d connected (total: %d)", num, _instance->_clientCount);
        break;

    case WStype_DISCONNECTED:
        if (_instance->_clientCount > 0) _instance->_clientCount--;
        Log::info("WS client #%d disconnected (total: %d)", num, _instance->_clientCount);
        break;

    default:
        break;
    }
}

// ============================================================================
// Broadcast radar frame as JSON to all WebSocket clients
// ============================================================================
void WebRadarServer::broadcastFrame(const RadarFrame& frame, uint32_t frameCount, uint32_t errorCount) {
    if (_clientCount == 0) return;

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

    _ws.broadcastTXT(json, len);
}
