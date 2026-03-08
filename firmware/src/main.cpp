#include <Arduino.h>
#include "radar_driver.h"
#include "config_manager.h"
#include "web_server.h"
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

    Log::info("========================================");
    Log::info("  HLK-LD2450 Human Radar - Phase 2");
    Log::info("  Board: ESP32-WROOM32");
    Log::info("  Realtime Web Radar v0.3");
    Log::info("========================================");

    // Load saved settings from NVS (WiFi, MQTT, device name)
    configManager.begin();

    // Init radar sensor
    Log::info("UART2: RX=GPIO%d, TX=GPIO%d", RADAR_RX_PIN, RADAR_TX_PIN);
    radar.begin(RADAR_RX_PIN, RADAR_TX_PIN);
    Log::info("Radar initialized at %lu baud", RADAR_BAUD);

    // Init WiFi + Web Server + WebSocket
    webServer.begin();

    Log::info("========================================");
    Log::info("  Radar:    http://%s", webServer.getIP().c_str());
    Log::info("  Settings: http://%s/settings", webServer.getIP().c_str());
    Log::info("========================================");
}

// ============================================================================
// Main Loop
// ============================================================================
void loop() {
    webServer.loop();

    if (radar.update()) {
        const RadarFrame& frame = radar.getLatestFrame();
        Log::printFrame(frame, radar.getFrameCount(), radar.getErrorCount());
        webServer.broadcastFrame(frame, radar.getFrameCount(), radar.getErrorCount());
    }
}
