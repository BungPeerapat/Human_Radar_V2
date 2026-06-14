#ifndef WEB_SERVER_H
#define WEB_SERVER_H

#include <Arduino.h>
#include <WiFi.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <WebServer.h>
#include <WebSocketsServer.h>
#include "wifi_config.h"
#include "radar_types.h"
#include "web_page.h"
#include "web_settings.h"
#include "config_manager.h"

// ============================================================================
// WebRadarServer: WiFi + HTTP + WebSocket for realtime radar display
// ============================================================================
class WebRadarServer {
public:
    void begin();
    void loop();
    void broadcastFrame(const RadarFrame& frame, uint32_t frameCount, uint32_t errorCount);

    // Runtime WiFi keep-alive: detects STA drops, and when "Auto Find WiFi" is on,
    // keeps an AP rescue up + retries STA until the network returns. Call from loop().
    void maintainWifi();

    bool isReady() const { return _ready; }
    String getIP() const { return _ip; }

private:
    WebServer        _http{WEB_SERVER_PORT};
    WebSocketsServer _ws{WEBSOCKET_PORT};
    DNSServer        _dns;
    bool             _ready = false;
    bool             _isAP = false;
    String           _ip;
    uint8_t          _clientCount = 0;

    // Auto-reconnect / rescue-AP runtime state (see maintainWifi()).
    uint32_t         _lastWifiCheckMs = 0;
    uint32_t         _lastStaRetryMs = 0;
    bool             _staWasConnected = false;

    void setupWiFi();
    void startRescueAp();
    void setupHTTP();
    void setupWebSocket();
    void setupMDNS();

    // API handlers
    void handleGetConfig();
    void handleSaveConfig();
    void handleResetConfig();
    void handleGetAlert();
    void handleSaveAlert();
    void handleTestAlert();

    static void onWebSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length);
};

extern WebRadarServer webServer;

#endif // WEB_SERVER_H
