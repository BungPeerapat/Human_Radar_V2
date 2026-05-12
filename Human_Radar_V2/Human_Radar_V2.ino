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
 *    - PubSubClient by Nick O'Leary (v2.8+)
 *    - ArduinoJson by Benoit Blanchon (v7.x)
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
 *    ESP32 GPIO26      ---> [R 330Ω] -> LED -> GND
 *                       \-> Buzzer (3.3V active) -> GND
 *
 *  Usage:
 *    1. Upload this sketch
 *    2. Connect phone/PC to WiFi "HumanRadar" (password: radar1234)
 *    3. Open browser -> http://192.168.4.1
 *    4. See realtime radar visualization!
 * ============================================================================
 */

#include <WiFi.h>
#include <esp_task_wdt.h>

#include "radar_driver.h"
#include "config_manager.h"
#include "web_server.h"
#include "mqtt_client.h"
#include "alert_pattern.h"
#include "logger.h"

// ============================================================================
// Pin Configuration - ESP32-WROOM32
// ============================================================================
static constexpr int RADAR_RX_PIN = 16;  // ESP32 RX <- LD2450 TX
static constexpr int RADAR_TX_PIN = 17;  // ESP32 TX -> LD2450 RX
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
        if (t.x == 0 && t.y == 0) continue;          // skip empty slot
        if (maxRangeMm > 0 && t.distance > maxRangeMm) continue;
        n++;
    }
    return n;
}

/**
 * Apply user-configured DetectionZones from NVS. If any zone is enabled, drop
 * targets that don't fall inside at least one enabled zone. If none are enabled
 * the frame passes through unchanged so a user with no zones still sees every
 * target the sensor reports.
 */
static void filterByDetectionZones(RadarFrame& frame, const DetectionZone* zones) {
    bool anyEnabled = false;
    for (uint8_t z = 0; z < 3; z++) {
        if (zones[z].enabled) { anyEnabled = true; break; }
    }
    if (!anyEnabled) return;

    for (uint8_t i = 0; i < RADAR_MAX_TARGETS; i++) {
        RadarTarget& t = frame.targets[i];
        if (!t.present) continue;
        bool inside = false;
        for (uint8_t z = 0; z < 3 && !inside; z++) {
            if (!zones[z].enabled) continue;
            int16_t xmin = zones[z].x1 < zones[z].x2 ? zones[z].x1 : zones[z].x2;
            int16_t xmax = zones[z].x1 > zones[z].x2 ? zones[z].x1 : zones[z].x2;
            int16_t ymin = zones[z].y1 < zones[z].y2 ? zones[z].y1 : zones[z].y2;
            int16_t ymax = zones[z].y1 > zones[z].y2 ? zones[z].y1 : zones[z].y2;
            if (t.x >= xmin && t.x <= xmax && t.y >= ymin && t.y <= ymax) inside = true;
        }
        if (!inside) {
            t.present  = false;
            t.x = 0; t.y = 0; t.speed = 0; t.distance = 0; t.angle = 0.0f;
            if (frame.targetCount > 0) frame.targetCount--;
        }
    }
}

/**
 * Drop "ghost" targets the LD2450 keeps reporting at the exact last (x,y) after
 * the real person has walked out of view. mmWave detects micro-jitter from real
 * stationary people (breathing, sway), so a target whose coordinates do NOT
 * change *at all* for > GHOST_TIMEOUT_MS is almost certainly the sensor's
 * internal tracker holding onto a stale slot — we mark it absent so downstream
 * (web UI, MQTT, alert pattern) treats it as gone.
 */
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
            // Stuck at the exact same (x,y) for too long -> declare ghost.
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

    // Alert pattern (LED + buzzer on GPIO26). Init early so WiFi connect blink works.
    alertPattern.begin(ALERT_PIN);

    // Load saved settings from NVS (WiFi, MQTT, device name)
    configManager.begin();

    // Apply persisted alert config (filled by Phase 2 once /api/alert lands)
    alertPattern.setConfig(configManager.getAlertConfig());

    // Init radar sensor
    Log::info(TAG_SENSOR, "UART2: RX=GPIO%d, TX=GPIO%d", RADAR_RX_PIN, RADAR_TX_PIN);
    radar.begin(RADAR_RX_PIN, RADAR_TX_PIN);
    Log::info(TAG_SENSOR, "Radar initialized at %lu baud", RADAR_BAUD);

    // Start the slow WiFi indicator before begin() (begin() blocks while joining)
    alertPattern.onWifiConnecting();

    // Init WiFi + Web Server + WebSocket
    webServer.begin();

    // Pulse the "connected" indicator if STA join succeeded
    if (WiFi.status() == WL_CONNECTED) {
        alertPattern.onWifiConnected();
    } else {
        // AP mode counts as "online" from the device's point of view.
        alertPattern.onWifiConnected();
    }

    // Init MQTT (only connects if enabled + configured)
    mqttClient.begin();

    // Task watchdog — auto-reset if loop() hangs for more than 15 seconds.
    // (Just-in-case safety net for rare lock-ups; healthy frames take <50ms.)
    esp_task_wdt_init(15, true);
    esp_task_wdt_add(NULL);

    Log::info(TAG_SYSTEM, "========================================");
    Log::info(TAG_SYSTEM, "  Radar:    http://%s", webServer.getIP().c_str());
    Log::info(TAG_SYSTEM, "  Settings: http://%s/settings", webServer.getIP().c_str());
    Log::info(TAG_SYSTEM, "  MQTT:     %s", mqttClient.getStatusText());
    Log::info(TAG_SYSTEM, "========================================");
}

// ============================================================================
// WiFi state monitor (STA mode only - AP mode never "drops")
// ============================================================================
static void monitorWifi() {
    static uint32_t lastCheck = 0;
    static bool     wasConnected = (WiFi.status() == WL_CONNECTED);

    const uint32_t now = millis();
    if (now - lastCheck < 1000) return;
    lastCheck = now;

    // AP mode: nothing to watch
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
    // 1. Handle web server / websocket clients
    webServer.loop();

    // 2. Handle MQTT connection
    mqttClient.loop();

    // 3. WiFi state edge detection (no-op until status flips)
    monitorWifi();

    // Feed the watchdog every loop iteration.
    esp_task_wdt_reset();

    // 4. Read radar data
    if (radar.update()) {
        // Local copy so we can rewrite ghost slots before broadcasting.
        RadarFrame frame = radar.getLatestFrame();
        filterByDetectionZones(frame, configManager.get().zones);
        filterGhostTargets(frame);

        // Broadcast to all WebSocket clients (browser)
        webServer.broadcastFrame(frame, radar.getFrameCount(), radar.getErrorCount());

        // Publish to MQTT broker
        mqttClient.publishFrame(frame, radar.getFrameCount(), radar.getErrorCount());

        // Feed debounced count into the alert state machine
        const auto& acfg = alertPattern.getConfig();
        const uint8_t active = countActiveTargets(frame, acfg.maxRangeMm);
        alertPattern.onTargetCount(active);
    }

    // 5. Pin scheduler (non-blocking)
    alertPattern.update();
}
