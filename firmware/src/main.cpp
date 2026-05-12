#include <Arduino.h>
#include <WiFi.h>

#include "radar_driver.h"
#include "config_manager.h"
#include "web_server.h"
#include "mqtt_client.h"
#include "alert_pattern.h"
#include "logger.h"

// ============================================================================
// Pin Configuration - ESP32-WROOM32
// ============================================================================
static constexpr int RADAR_RX_PIN = 16;
static constexpr int RADAR_TX_PIN = 17;
static constexpr int ALERT_PIN    = 26;  // LED + buzzer (parallel, active-HIGH)

// ============================================================================
// Globals
// ============================================================================
RadarDriver radar;

// ============================================================================
// Helpers
// ============================================================================
static uint8_t countActiveTargets(const RadarFrame& frame, uint16_t maxRangeMm) {
    uint8_t n = 0;
    for (uint8_t i = 0; i < RADAR_MAX_TARGETS; i++) {
        const RadarTarget& t = frame.targets[i];
        if (!t.present) continue;
        if (t.x == 0 && t.y == 0) continue;
        if (maxRangeMm > 0 && t.distance > maxRangeMm) continue;
        n++;
    }
    return n;
}

// Drop "ghost" targets the LD2450 keeps reporting at the exact last (x,y) after
// the real person has walked out of view. See Human_Radar_V2.ino for the long
// explanation.
static constexpr uint32_t GHOST_TIMEOUT_MS = 2000;

static void filterGhostTargets(RadarFrame& frame) {
    static int16_t  lastX[RADAR_MAX_TARGETS]      = {0};
    static int16_t  lastY[RADAR_MAX_TARGETS]      = {0};
    static uint32_t lastChange[RADAR_MAX_TARGETS] = {0};
    static bool     hadTarget[RADAR_MAX_TARGETS]  = {false};

    const uint32_t now = millis();
    for (uint8_t i = 0; i < RADAR_MAX_TARGETS; i++) {
        RadarTarget& t = frame.targets[i];
        if (!t.present) {
            lastX[i] = 0;
            lastY[i] = 0;
            lastChange[i] = 0;
            hadTarget[i] = false;
            continue;
        }
        if (!hadTarget[i] || t.x != lastX[i] || t.y != lastY[i]) {
            lastX[i] = t.x;
            lastY[i] = t.y;
            lastChange[i] = now;
            hadTarget[i] = true;
        } else if (now - lastChange[i] > GHOST_TIMEOUT_MS) {
            t.present  = false;
            t.x        = 0;
            t.y        = 0;
            t.speed    = 0;
            t.distance = 0;
            t.angle    = 0.0f;
            if (frame.targetCount > 0) frame.targetCount--;
            hadTarget[i] = false;
            lastChange[i] = 0;
        }
    }
}

// ============================================================================
// Setup
// ============================================================================
void setup() {
    Serial.begin(115200);
    delay(1000);

    Log::info(TAG_SYSTEM, "========================================");
    Log::info(TAG_SYSTEM, "  HLK-LD2450 Human Radar v%s", FW_VERSION);
    Log::info(TAG_SYSTEM, "  Board: ESP32-WROOM32");
    Log::info(TAG_SYSTEM, "  MQTT + Remote Config + Log Viewer");
    Log::info(TAG_SYSTEM, "========================================");

    alertPattern.begin(ALERT_PIN);

    configManager.begin();
    alertPattern.setConfig(configManager.getAlertConfig());

    Log::info(TAG_SENSOR, "UART2: RX=GPIO%d, TX=GPIO%d", RADAR_RX_PIN, RADAR_TX_PIN);
    radar.begin(RADAR_RX_PIN, RADAR_TX_PIN);
    Log::info(TAG_SENSOR, "Radar initialized at %lu baud", RADAR_BAUD);

    alertPattern.onWifiConnecting();
    webServer.begin();
    alertPattern.onWifiConnected();

    mqttClient.begin();

    Log::info(TAG_SYSTEM, "========================================");
    Log::info(TAG_SYSTEM, "  Radar:    http://%s", webServer.getIP().c_str());
    Log::info(TAG_SYSTEM, "  Settings: http://%s/settings", webServer.getIP().c_str());
    Log::info(TAG_SYSTEM, "  MQTT:     %s", mqttClient.getStatusText());
    Log::info(TAG_SYSTEM, "========================================");
}

// ============================================================================
// WiFi state monitor (STA mode only)
// ============================================================================
static void monitorWifi() {
    static uint32_t lastCheck = 0;
    static bool     wasConnected = (WiFi.status() == WL_CONNECTED);

    const uint32_t now = millis();
    if (now - lastCheck < 1000) return;
    lastCheck = now;

    if (WiFi.getMode() == WIFI_AP) return;

    const bool nowConnected = (WiFi.status() == WL_CONNECTED);
    if (nowConnected != wasConnected) {
        if (nowConnected) {
            Log::info(TAG_SYSTEM, "WiFi reconnected");
            alertPattern.onWifiConnected();
        } else {
            Log::warn(TAG_SYSTEM, "WiFi link lost");
            alertPattern.onWifiDisconnected();
        }
        wasConnected = nowConnected;
    }
}

// ============================================================================
// Main Loop
// ============================================================================
void loop() {
    webServer.loop();
    mqttClient.loop();
    monitorWifi();

    if (radar.update()) {
        RadarFrame frame = radar.getLatestFrame();
        filterGhostTargets(frame);
        webServer.broadcastFrame(frame, radar.getFrameCount(), radar.getErrorCount());
        mqttClient.publishFrame(frame, radar.getFrameCount(), radar.getErrorCount());

        const auto& acfg = alertPattern.getConfig();
        const uint8_t active = countActiveTargets(frame, acfg.maxRangeMm);
        alertPattern.onTargetCount(active);
    }

    alertPattern.update();
}
