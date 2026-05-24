// Bridge firmware — minimal OTA pull. See platformio.ini for what's
// stripped vs the full Human_Radar_V2 firmware.
//
// Flow at boot:
//   1. Preferences.begin("hradar")     ← same NVS namespace as full fw
//   2. Read wifi_ssid / wifi_pass + mqtt_host/port/user/pass + dev_name
//   3. WiFi.begin(STA) + retry forever (no AP fallback — we trust NVS)
//   4. Connect to MQTT broker, publish status=online (retained)
//   5. Subscribe to humanradar/<dev_name>/cmd
//   6. On 'ota_pull' cmd → HTTPClient GET .bin → Update.write → restart
//   7. On 'restart' cmd → ESP.restart()
//   8. Heartbeat: LED on GPIO26 blinks every 3 s so user can tell the
//      bridge is alive without needing serial / app feedback.

#include <Arduino.h>
#include <WiFi.h>
#include <Preferences.h>
#include <PubSubClient.h>

#include "bridge_mqtt.h"
#include "bridge_ota.h"

#ifndef FW_VERSION
#define FW_VERSION "0.0.0-bridge"
#endif

static constexpr int   LED_PIN          = 26;
static constexpr uint32_t HEARTBEAT_MS  = 3000;
static constexpr uint32_t LED_ON_MS     = 100;
static constexpr uint32_t WIFI_RETRY_MS = 5000;

static char g_deviceName[33] = "HumanRadar";

static void readNvsConfig() {
    Preferences p;
    p.begin("hradar", true);  // read-only — bridge never writes settings
    if (p.isKey("dev_name")) {
        p.getString("dev_name", g_deviceName, sizeof(g_deviceName));
    }
    // wifi/mqtt creds are pulled directly inside bridgeMqttBegin().
    p.end();
}

static void connectWifiBlocking() {
    Preferences p;
    p.begin("hradar", true);
    char ssid[33] = {0};
    char pass[65] = {0};
    p.getString("wifi_ssid", ssid, sizeof(ssid));
    p.getString("wifi_pass", pass, sizeof(pass));
    p.end();

    if (strlen(ssid) == 0) {
        Serial.println("[bridge] No WiFi creds in NVS — bridge needs them, "
                       "halting. Re-flash full firmware via USB.");
        while (true) { digitalWrite(LED_PIN, HIGH); delay(100);
                       digitalWrite(LED_PIN, LOW);  delay(100); }
    }

    WiFi.mode(WIFI_STA);
    WiFi.setHostname(g_deviceName);
    Serial.printf("[bridge] Connecting WiFi: %s\n", ssid);
    WiFi.begin(ssid, pass);

    uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED) {
        digitalWrite(LED_PIN, (millis() / 250) & 1 ? HIGH : LOW);
        delay(50);
        if (millis() - start > WIFI_RETRY_MS) {
            Serial.println("[bridge] WiFi retry…");
            WiFi.disconnect();
            delay(200);
            WiFi.begin(ssid, pass);
            start = millis();
        }
    }
    digitalWrite(LED_PIN, LOW);
    Serial.printf("[bridge] WiFi connected. IP=%s  RSSI=%d\n",
                  WiFi.localIP().toString().c_str(), WiFi.RSSI());
}

void setup() {
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, LOW);
    Serial.begin(115200);
    delay(500);

    Serial.println();
    Serial.println("========================================");
    Serial.printf("  HumanRadar BRIDGE firmware v%s\n", FW_VERSION);
    Serial.println("  Only ota_pull + restart commands available");
    Serial.println("  Use full firmware for normal radar features");
    Serial.println("========================================");

    readNvsConfig();
    Serial.printf("[bridge] Device name: %s\n", g_deviceName);

    connectWifiBlocking();
    bridgeMqttBegin(g_deviceName, FW_VERSION);
}

void loop() {
    bridgeMqttLoop();

    // Heartbeat blink — short pulse every 3 s so the operator can see
    // the bridge is alive even without console access.
    static uint32_t lastHb = 0;
    static bool ledOn = false;
    uint32_t now = millis();
    if (!ledOn && now - lastHb >= HEARTBEAT_MS) {
        digitalWrite(LED_PIN, HIGH);
        ledOn = true;
        lastHb = now;
    } else if (ledOn && now - lastHb >= LED_ON_MS) {
        digitalWrite(LED_PIN, LOW);
        ledOn = false;
    }

    // Recover WiFi if it drops mid-run.
    if (WiFi.status() != WL_CONNECTED) {
        Serial.println("[bridge] WiFi lost — reconnecting");
        connectWifiBlocking();
    }
}
