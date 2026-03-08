/**
 * ============================================================================
 *  HLK-LD2450 Human Radar - Phase 2: Realtime Web Visualization
 * ============================================================================
 *  Board:  ESP32-WROOM32
 *  Sensor: HLK-LD2450 (mmWave radar, 3 targets, 10 Hz)
 *  IDE:    Arduino IDE 2.x
 *
 *  Required Libraries (install via Arduino Library Manager):
 *    - WebSockets by Markus Sattler (v2.4.0+)
 *
 *  Board Settings in Arduino IDE:
 *    Board:        "ESP32 Dev Module"
 *    Upload Speed: 921600
 *    Flash Mode:   DIO
 *    Flash Size:   4MB (32Mb)
 *    Partition:    Default 4MB with spiffs
 *
 *  Wiring:
 *    ESP32 GPIO16 (RX) <--- LD2450 TX
 *    ESP32 GPIO17 (TX) ---> LD2450 RX
 *    ESP32 5V          ---> LD2450 VCC (needs >200mA)
 *    ESP32 GND         ---> LD2450 GND
 *
 *  Usage:
 *    1. Upload this sketch
 *    2. Connect phone/PC to WiFi "HumanRadar" (password: radar1234)
 *    3. Open browser -> http://192.168.4.1
 *    4. See realtime radar visualization!
 * ============================================================================
 */

#include "radar_driver.h"
#include "config_manager.h"
#include "web_server.h"
#include "logger.h"

// ============================================================================
// Pin Configuration - ESP32-WROOM32
// ============================================================================
static constexpr int RADAR_RX_PIN = 16;  // ESP32 RX <- LD2450 TX
static constexpr int RADAR_TX_PIN = 17;  // ESP32 TX -> LD2450 RX

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
    // 1. Handle web server / websocket clients
    webServer.loop();

    // 2. Read radar data
    if (radar.update()) {
        const RadarFrame& frame = radar.getLatestFrame();

        // Send to Serial (debug)
        Log::printFrame(frame, radar.getFrameCount(), radar.getErrorCount());

        // Broadcast to all WebSocket clients (browser)
        webServer.broadcastFrame(frame, radar.getFrameCount(), radar.getErrorCount());
    }
}
