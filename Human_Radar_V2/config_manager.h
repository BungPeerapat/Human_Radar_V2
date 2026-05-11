#ifndef CONFIG_MANAGER_H
#define CONFIG_MANAGER_H

#include <Arduino.h>
#include <Preferences.h>

#include "alert_pattern.h"

// ============================================================================
// Firmware Version
// ============================================================================
#define FW_VERSION "0.5.0"

// ============================================================================
// Detection Zone (HLK-LD2450 supports 3 zones)
// ============================================================================
struct DetectionZone {
    bool     enabled;
    int16_t  x1, y1;   // top-left corner (mm)
    int16_t  x2, y2;   // bottom-right corner (mm)
};

// ============================================================================
// ConfigManager: Stores settings in ESP32 NVS (Non-Volatile Storage)
// Survives reboot and re-upload. No SPIFFS/files needed.
//
// NVS keys:
//   wifi_mode, wifi_ssid, wifi_pass
//   mqtt_en, mqtt_proto, mqtt_host, mqtt_port, mqtt_user, mqtt_pass
//   dev_name
//   pub_int     - publish interval (ms)
//   unm_dly     - unmanned delay (ms)
//   tgt_tout    - target timeout (ms)
//   multi_tgt   - multi-target mode (0/1)
//   sensitivity - sensitivity level (0-9)
//   zoneN_en, zoneN_x1/y1/x2/y2  (N=0,1,2)
// ============================================================================

struct DeviceConfig {
    // WiFi
    uint8_t  wifiMode;                // 0=AP, 1=STA
    char     wifiSSID[33];
    char     wifiPass[65];

    // MQTT
    uint8_t  mqttEnabled;             // 0=off, 1=on
    uint8_t  mqttProto;               // 0=ws, 1=wss, 2=mqtt/tcp, 3=mqtts/tls
    char     mqttHost[65];
    uint16_t mqttPort;
    char     mqttUser[33];
    char     mqttPass[65];

    // Device
    char     deviceName[33];

    // Sensor / Publish
    uint16_t publishIntervalMs;       // MQTT publish rate (50-2000ms, default 100)
    uint16_t unmannedDelayMs;         // time before reporting "no presence" (1000-60000ms, default 5000)
    uint16_t targetTimeoutMs;         // target gone timeout (100-10000ms, default 1000)
    uint8_t  multiTargetMode;         // 0=single, 1=multi (up to 3)
    uint8_t  sensitivity;             // 0-9 (software filter threshold)

    // Detection Zones (3 zones, LD2450 supports hardware zone filtering)
    DetectionZone zones[3];

    // Alert / LED+Buzzer (GPIO26)
    AlertPattern::Config alert;
};

class ConfigManager {
public:
    void begin();

    const DeviceConfig& get() const { return _cfg; }

    void setWiFi(uint8_t mode, const char* ssid, const char* pass);
    void setMQTT(uint8_t enabled, uint8_t proto, const char* host, uint16_t port, const char* user, const char* pass);
    void setDeviceName(const char* name);

    // Sensor config setters
    void setPublishInterval(uint16_t ms);
    void setUnmannedDelay(uint16_t ms);
    void setTargetTimeout(uint16_t ms);
    void setMultiTargetMode(uint8_t mode);
    void setSensitivity(uint8_t level);
    void setZone(uint8_t idx, bool enabled, int16_t x1, int16_t y1, int16_t x2, int16_t y2);

    // Alert config (LED+buzzer on GPIO26)
    const AlertPattern::Config& getAlertConfig() const { return _cfg.alert; }
    void setAlertConfig(const AlertPattern::Config& a);

    void resetToDefaults();

    bool hasWiFiConfig() const { return _cfg.wifiMode == 1 && strlen(_cfg.wifiSSID) > 0; }

private:
    Preferences  _prefs;
    DeviceConfig _cfg;

    void loadFromNVS();
    void applyDefaults();
    void saveZone(uint8_t idx);
};

extern ConfigManager configManager;

#endif // CONFIG_MANAGER_H
