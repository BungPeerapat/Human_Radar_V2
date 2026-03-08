#include "web_server.h"
#include "logger.h"

// Global instance
WebRadarServer webServer;

// Static callback needs access to instance
static WebRadarServer* _instance = nullptr;

void WebRadarServer::begin() {
    _instance = this;
    setupWiFi();
    setupHTTP();
    setupWebSocket();
    _ready = true;
}

void WebRadarServer::loop() {
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
    _ip = WiFi.softAPIP().toString();
    Log::info("WiFi AP started: %s (pass: %s)", WIFI_AP_SSID, WIFI_AP_PASS);
    Log::info("IP: %s", _ip.c_str());
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

    _http.begin();
    Log::info("HTTP server on port %d", WEB_SERVER_PORT);
}

// ============================================================================
// API: Get Config (JSON)
// ============================================================================
void WebRadarServer::handleGetConfig() {
    const DeviceConfig& cfg = configManager.get();

    char json[512];
    snprintf(json, sizeof(json),
        "{\"wm\":%d,\"ws\":\"%s\",\"wp\":\"%s\","
        "\"mh\":\"%s\",\"mp\":%d,\"mu\":\"%s\",\"mpp\":\"%s\","
        "\"dn\":\"%s\",\"ip\":\"%s\"}",
        cfg.wifiMode, cfg.wifiSSID, cfg.wifiPass,
        cfg.mqttHost, cfg.mqttPort, cfg.mqttUser, cfg.mqttPass,
        cfg.deviceName, _ip.c_str()
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
    String mh = getJsonStr("mh");
    int mp = getJsonInt("mp", 1883);
    String mu = getJsonStr("mu");
    String mpp = getJsonStr("mpp");
    configManager.setMQTT(mh.c_str(), (uint16_t)mp, mu.c_str(), mpp.c_str());

    // Save device name
    String dn = getJsonStr("dn");
    if (dn.length() > 0) {
        configManager.setDeviceName(dn.c_str());
    }

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
