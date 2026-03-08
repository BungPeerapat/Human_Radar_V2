#include "config_manager.h"
#include "logger.h"

ConfigManager configManager;

static const char* NVS_NAMESPACE = "hradar";

void ConfigManager::begin() {
    applyDefaults();
    loadFromNVS();

    Log::info("Config loaded: mode=%s, SSID=[%s], device=[%s]",
              _cfg.wifiMode == 1 ? "STA" : "AP",
              _cfg.wifiSSID,
              _cfg.deviceName);

    if (strlen(_cfg.mqttHost) > 0) {
        Log::info("MQTT: %s:%d, user=[%s]", _cfg.mqttHost, _cfg.mqttPort, _cfg.mqttUser);
    }
}

void ConfigManager::applyDefaults() {
    memset(&_cfg, 0, sizeof(_cfg));
    _cfg.wifiMode = 0;  // AP mode
    strlcpy(_cfg.deviceName, "HumanRadar", sizeof(_cfg.deviceName));
    _cfg.mqttPort = 1883;
}

void ConfigManager::loadFromNVS() {
    _prefs.begin(NVS_NAMESPACE, true);  // read-only

    _cfg.wifiMode = _prefs.getUChar("wifi_mode", 0);
    _prefs.getString("wifi_ssid", _cfg.wifiSSID, sizeof(_cfg.wifiSSID));
    _prefs.getString("wifi_pass", _cfg.wifiPass, sizeof(_cfg.wifiPass));
    _prefs.getString("mqtt_host", _cfg.mqttHost, sizeof(_cfg.mqttHost));
    _cfg.mqttPort = _prefs.getUShort("mqtt_port", 1883);
    _prefs.getString("mqtt_user", _cfg.mqttUser, sizeof(_cfg.mqttUser));
    _prefs.getString("mqtt_pass", _cfg.mqttPass, sizeof(_cfg.mqttPass));
    _prefs.getString("dev_name", _cfg.deviceName, sizeof(_cfg.deviceName));

    _prefs.end();
}

void ConfigManager::setWiFi(uint8_t mode, const char* ssid, const char* pass) {
    _cfg.wifiMode = mode;
    strlcpy(_cfg.wifiSSID, ssid, sizeof(_cfg.wifiSSID));
    strlcpy(_cfg.wifiPass, pass, sizeof(_cfg.wifiPass));

    _prefs.begin(NVS_NAMESPACE, false);  // read-write
    _prefs.putUChar("wifi_mode", mode);
    _prefs.putString("wifi_ssid", ssid);
    _prefs.putString("wifi_pass", pass);
    _prefs.end();

    Log::info("WiFi config saved: mode=%s, SSID=[%s]", mode == 1 ? "STA" : "AP", ssid);
}

void ConfigManager::setMQTT(const char* host, uint16_t port, const char* user, const char* pass) {
    strlcpy(_cfg.mqttHost, host, sizeof(_cfg.mqttHost));
    _cfg.mqttPort = port;
    strlcpy(_cfg.mqttUser, user, sizeof(_cfg.mqttUser));
    strlcpy(_cfg.mqttPass, pass, sizeof(_cfg.mqttPass));

    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putString("mqtt_host", host);
    _prefs.putUShort("mqtt_port", port);
    _prefs.putString("mqtt_user", user);
    _prefs.putString("mqtt_pass", pass);
    _prefs.end();

    Log::info("MQTT config saved: %s:%d", host, port);
}

void ConfigManager::setDeviceName(const char* name) {
    strlcpy(_cfg.deviceName, name, sizeof(_cfg.deviceName));

    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putString("dev_name", name);
    _prefs.end();
}

void ConfigManager::resetToDefaults() {
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.clear();
    _prefs.end();

    applyDefaults();
    Log::info("Config reset to defaults");
}
