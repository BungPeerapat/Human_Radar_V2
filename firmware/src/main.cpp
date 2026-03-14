#include <Arduino.h>
#include "radar_driver.h"
#include "config_manager.h"
#include "web_server.h"
#include "mqtt_client.h"
#include "logger.h"

// ============================================================================
// Pin Configuration - ESP32-WROOM32
// ============================================================================
static constexpr int RADAR_RX_PIN = 16;
static constexpr int RADAR_TX_PIN = 17;

// ============================================================================
// Globals
// ============================================================================
RadarDriver radar;

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

    // Load saved settings from NVS (WiFi, MQTT, device name)
    configManager.begin();

    // Init radar sensor
    Log::info(TAG_SENSOR, "UART2: RX=GPIO%d, TX=GPIO%d", RADAR_RX_PIN, RADAR_TX_PIN);
    radar.begin(RADAR_RX_PIN, RADAR_TX_PIN);
    Log::info(TAG_SENSOR, "Radar initialized at %lu baud", RADAR_BAUD);

    // Init WiFi + Web Server + WebSocket
    webServer.begin();

    // Init MQTT (only connects if enabled + configured)
    mqttClient.begin();

    Log::info(TAG_SYSTEM, "========================================");
    Log::info(TAG_SYSTEM, "  Radar:    http://%s", webServer.getIP().c_str());
    Log::info(TAG_SYSTEM, "  Settings: http://%s/settings", webServer.getIP().c_str());
    Log::info(TAG_SYSTEM, "  MQTT:     %s", mqttClient.getStatusText());
    Log::info(TAG_SYSTEM, "========================================");
}

// ============================================================================
// Main Loop
// ============================================================================
void loop() {
    // 1. Handle web server / websocket clients
    webServer.loop();

    // 2. Handle MQTT connection
    mqttClient.loop();

    // 3. Read radar data
    if (radar.update()) {
        const RadarFrame& frame = radar.getLatestFrame();

        // Broadcast to all WebSocket clients (browser)
        webServer.broadcastFrame(frame, radar.getFrameCount(), radar.getErrorCount());

        // Publish to MQTT broker
        mqttClient.publishFrame(frame, radar.getFrameCount(), radar.getErrorCount());
    }
}
