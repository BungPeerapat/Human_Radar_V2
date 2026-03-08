#ifndef CONFIG_MANAGER_H
#define CONFIG_MANAGER_H

#include <Arduino.h>
#include <Preferences.h>

// ============================================================================
// ConfigManager: Stores settings in ESP32 NVS (Non-Volatile Storage)
// Survives reboot and re-upload. No SPIFFS/files needed.
//
// Stored keys:
//   wifi_ssid     - WiFi SSID to connect (Station mode)
//   wifi_pass     - WiFi Password
//   wifi_mode     - 0=AP mode, 1=Station mode
//   mqtt_host     - MQTT broker hostname/IP  (reserved for future)
//   mqtt_port     - MQTT broker port          (reserved for future)
//   mqtt_user     - MQTT username             (reserved for future)
//   mqtt_pass     - MQTT password             (reserved for future)
// ============================================================================

struct DeviceConfig {
    // WiFi
    uint8_t  wifiMode;                // 0=AP, 1=STA
    char     wifiSSID[33];            // max 32 chars + null
    char     wifiPass[65];            // max 64 chars + null

    // MQTT (reserved for future)
    char     mqttHost[65];
    uint16_t mqttPort;
    char     mqttUser[33];
    char     mqttPass[65];

    // Device
    char     deviceName[33];
};

class ConfigManager {
public:
    /** Load config from NVS. Call once in setup(). */
    void begin();

    /** Get current config (read-only). */
    const DeviceConfig& get() const { return _cfg; }

    /** Save WiFi settings to NVS. */
    void setWiFi(uint8_t mode, const char* ssid, const char* pass);

    /** Save MQTT settings to NVS. */
    void setMQTT(const char* host, uint16_t port, const char* user, const char* pass);

    /** Save device name to NVS. */
    void setDeviceName(const char* name);

    /** Reset all settings to defaults. */
    void resetToDefaults();

    /** Check if WiFi Station mode is configured (has SSID). */
    bool hasWiFiConfig() const { return _cfg.wifiMode == 1 && strlen(_cfg.wifiSSID) > 0; }

private:
    Preferences  _prefs;
    DeviceConfig _cfg;

    void loadFromNVS();
    void applyDefaults();
};

extern ConfigManager configManager;

#endif // CONFIG_MANAGER_H
