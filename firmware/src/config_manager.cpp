#include "config_manager.h"
#include "logger.h"

ConfigManager configManager;

static const char* NVS_NAMESPACE = "hradar";

void ConfigManager::begin() {
    applyDefaults();
    loadFromNVS();

    Log::info(TAG_CONFIG, "Config loaded: mode=%s, SSID=[%s], device=[%s]",
              _cfg.wifiMode == 1 ? "STA" : "AP",
              _cfg.wifiSSID,
              _cfg.deviceName);

    static const char* protoNames[] = {"ws","wss","mqtt/tcp","mqtts/tls"};
    if (_cfg.mqttEnabled && strlen(_cfg.mqttHost) > 0) {
        Log::info(TAG_CONFIG, "MQTT: enabled, proto=%s, %s:%d",
                  protoNames[_cfg.mqttProto & 3], _cfg.mqttHost, _cfg.mqttPort);
    } else {
        Log::info(TAG_CONFIG, "MQTT: disabled");
    }

    Log::info(TAG_CONFIG, "Sensor: pubInt=%dms, unmanDly=%dms, tgtTout=%dms, multi=%d, sens=%d",
              _cfg.publishIntervalMs, _cfg.unmannedDelayMs, _cfg.targetTimeoutMs,
              _cfg.multiTargetMode, _cfg.sensitivity);

    for (int i = 0; i < 3; i++) {
        if (_cfg.zones[i].enabled) {
            Log::info(TAG_CONFIG, "Zone%d: (%d,%d)-(%d,%d)",
                      i + 1, _cfg.zones[i].x1, _cfg.zones[i].y1,
                      _cfg.zones[i].x2, _cfg.zones[i].y2);
        }
    }
}

void ConfigManager::applyDefaults() {
    memset(&_cfg, 0, sizeof(_cfg));
    _cfg.wifiMode = 0;
    strlcpy(_cfg.deviceName, "HumanRadar", sizeof(_cfg.deviceName));
    _cfg.mqttProto = 2;        // mqtt/tcp
    _cfg.mqttPort = 1883;
    _cfg.publishIntervalMs = 100;
    _cfg.unmannedDelayMs = 5000;
    _cfg.targetTimeoutMs = 1000;
    _cfg.multiTargetMode = 1;  // multi-target by default
    _cfg.sensitivity = 5;      // mid-range

    // Default zone 1: full 6m forward, +-3m wide
    _cfg.zones[0] = {true, -3000, 0, 3000, 6000};
    _cfg.zones[1] = {false, 0, 0, 0, 0};
    _cfg.zones[2] = {false, 0, 0, 0, 0};

    // Alert defaults (matches AlertPattern::Config defaults; restated for clarity)
    _cfg.alert.alertEnabled  = true;
    _cfg.alert.ledWifiEnabled = true;
    _cfg.alert.beepShortMs   = 200;
    _cfg.alert.beepLongMs    = 800;
    _cfg.alert.beepGapMs     = 200;
    _cfg.alert.debounceMs    = 500;
    _cfg.alert.maxRangeMm    = 6000;
}

void ConfigManager::loadFromNVS() {
    _prefs.begin(NVS_NAMESPACE, true);

    // WiFi
    _cfg.wifiMode = _prefs.getUChar("wifi_mode", 0);
    _prefs.getString("wifi_ssid", _cfg.wifiSSID, sizeof(_cfg.wifiSSID));
    _prefs.getString("wifi_pass", _cfg.wifiPass, sizeof(_cfg.wifiPass));

    // MQTT
    _cfg.mqttEnabled = _prefs.getUChar("mqtt_en", 0);
    _cfg.mqttProto = _prefs.getUChar("mqtt_proto", 2);
    _prefs.getString("mqtt_host", _cfg.mqttHost, sizeof(_cfg.mqttHost));
    _cfg.mqttPort = _prefs.getUShort("mqtt_port", 1883);
    _prefs.getString("mqtt_user", _cfg.mqttUser, sizeof(_cfg.mqttUser));
    _prefs.getString("mqtt_pass", _cfg.mqttPass, sizeof(_cfg.mqttPass));

    // Device
    _prefs.getString("dev_name", _cfg.deviceName, sizeof(_cfg.deviceName));

    // Sensor
    _cfg.publishIntervalMs = _prefs.getUShort("pub_int", 100);
    _cfg.unmannedDelayMs = _prefs.getUShort("unm_dly", 5000);
    _cfg.targetTimeoutMs = _prefs.getUShort("tgt_tout", 1000);
    _cfg.multiTargetMode = _prefs.getUChar("multi_tgt", 1);
    _cfg.sensitivity = _prefs.getUChar("sensitivity", 5);

    // Detection zones
    char key[12];
    for (int i = 0; i < 3; i++) {
        snprintf(key, sizeof(key), "z%d_en", i);
        _cfg.zones[i].enabled = _prefs.getUChar(key, i == 0 ? 1 : 0);
        snprintf(key, sizeof(key), "z%d_x1", i);
        _cfg.zones[i].x1 = _prefs.getShort(key, i == 0 ? -3000 : 0);
        snprintf(key, sizeof(key), "z%d_y1", i);
        _cfg.zones[i].y1 = _prefs.getShort(key, 0);
        snprintf(key, sizeof(key), "z%d_x2", i);
        _cfg.zones[i].x2 = _prefs.getShort(key, i == 0 ? 3000 : 0);
        snprintf(key, sizeof(key), "z%d_y2", i);
        _cfg.zones[i].y2 = _prefs.getShort(key, i == 0 ? 6000 : 0);
    }

    // Alert / LED+Buzzer
    _cfg.alert.alertEnabled   = _prefs.getUChar ("al_en",  1) != 0;
    _cfg.alert.ledWifiEnabled = _prefs.getUChar ("al_lw",  1) != 0;
    _cfg.alert.beepShortMs    = _prefs.getUShort("al_bs",  200);
    _cfg.alert.beepLongMs     = _prefs.getUShort("al_bl",  800);
    _cfg.alert.beepGapMs      = _prefs.getUShort("al_bg",  200);
    _cfg.alert.debounceMs     = _prefs.getUShort("al_db",  500);
    _cfg.alert.maxRangeMm     = _prefs.getUShort("al_mr",  6000);

    _prefs.end();
}

// ============================================================================
// WiFi
// ============================================================================
void ConfigManager::setWiFi(uint8_t mode, const char* ssid, const char* pass) {
    _cfg.wifiMode = mode;
    strlcpy(_cfg.wifiSSID, ssid, sizeof(_cfg.wifiSSID));
    strlcpy(_cfg.wifiPass, pass, sizeof(_cfg.wifiPass));

    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putUChar("wifi_mode", mode);
    _prefs.putString("wifi_ssid", ssid);
    _prefs.putString("wifi_pass", pass);
    _prefs.end();

    Log::info(TAG_CONFIG, "WiFi saved: mode=%s, SSID=[%s]", mode == 1 ? "STA" : "AP", ssid);
}

// ============================================================================
// MQTT
// ============================================================================
void ConfigManager::setMQTT(uint8_t enabled, uint8_t proto, const char* host, uint16_t port, const char* user, const char* pass) {
    _cfg.mqttEnabled = enabled;
    _cfg.mqttProto = proto;
    strlcpy(_cfg.mqttHost, host, sizeof(_cfg.mqttHost));
    _cfg.mqttPort = port;
    strlcpy(_cfg.mqttUser, user, sizeof(_cfg.mqttUser));
    strlcpy(_cfg.mqttPass, pass, sizeof(_cfg.mqttPass));

    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putUChar("mqtt_en", enabled);
    _prefs.putUChar("mqtt_proto", proto);
    _prefs.putString("mqtt_host", host);
    _prefs.putUShort("mqtt_port", port);
    _prefs.putString("mqtt_user", user);
    _prefs.putString("mqtt_pass", pass);
    _prefs.end();

    static const char* protoNames[] = {"ws","wss","mqtt/tcp","mqtts/tls"};
    Log::info(TAG_CONFIG, "MQTT saved: %s, proto=%s, %s:%d",
              enabled ? "ON" : "OFF", protoNames[proto & 3], host, port);
}

// ============================================================================
// Device
// ============================================================================
void ConfigManager::setDeviceName(const char* name) {
    strlcpy(_cfg.deviceName, name, sizeof(_cfg.deviceName));
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putString("dev_name", name);
    _prefs.end();
}

// ============================================================================
// Sensor Config
// ============================================================================
void ConfigManager::setPublishInterval(uint16_t ms) {
    _cfg.publishIntervalMs = constrain(ms, 50, 2000);
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putUShort("pub_int", _cfg.publishIntervalMs);
    _prefs.end();
    Log::info(TAG_CONFIG, "Publish interval: %dms", _cfg.publishIntervalMs);
}

void ConfigManager::setUnmannedDelay(uint16_t ms) {
    _cfg.unmannedDelayMs = constrain(ms, 1000, 60000);
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putUShort("unm_dly", _cfg.unmannedDelayMs);
    _prefs.end();
    Log::info(TAG_CONFIG, "Unmanned delay: %dms", _cfg.unmannedDelayMs);
}

void ConfigManager::setTargetTimeout(uint16_t ms) {
    _cfg.targetTimeoutMs = constrain(ms, 100, 10000);
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putUShort("tgt_tout", _cfg.targetTimeoutMs);
    _prefs.end();
    Log::info(TAG_CONFIG, "Target timeout: %dms", _cfg.targetTimeoutMs);
}

void ConfigManager::setMultiTargetMode(uint8_t mode) {
    _cfg.multiTargetMode = mode ? 1 : 0;
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putUChar("multi_tgt", _cfg.multiTargetMode);
    _prefs.end();
    Log::info(TAG_CONFIG, "Multi-target: %s", _cfg.multiTargetMode ? "ON" : "OFF");
}

void ConfigManager::setSensitivity(uint8_t level) {
    _cfg.sensitivity = min(level, (uint8_t)9);
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putUChar("sensitivity", _cfg.sensitivity);
    _prefs.end();
    Log::info(TAG_CONFIG, "Sensitivity: %d", _cfg.sensitivity);
}

void ConfigManager::setZone(uint8_t idx, bool enabled, int16_t x1, int16_t y1, int16_t x2, int16_t y2) {
    if (idx >= 3) return;
    _cfg.zones[idx] = {enabled, x1, y1, x2, y2};
    saveZone(idx);
    Log::info(TAG_CONFIG, "Zone%d: %s (%d,%d)-(%d,%d)",
              idx + 1, enabled ? "ON" : "OFF", x1, y1, x2, y2);
}

void ConfigManager::saveZone(uint8_t idx) {
    char key[12];
    _prefs.begin(NVS_NAMESPACE, false);
    snprintf(key, sizeof(key), "z%d_en", idx);
    _prefs.putUChar(key, _cfg.zones[idx].enabled ? 1 : 0);
    snprintf(key, sizeof(key), "z%d_x1", idx);
    _prefs.putShort(key, _cfg.zones[idx].x1);
    snprintf(key, sizeof(key), "z%d_y1", idx);
    _prefs.putShort(key, _cfg.zones[idx].y1);
    snprintf(key, sizeof(key), "z%d_x2", idx);
    _prefs.putShort(key, _cfg.zones[idx].x2);
    snprintf(key, sizeof(key), "z%d_y2", idx);
    _prefs.putShort(key, _cfg.zones[idx].y2);
    _prefs.end();
}

// ============================================================================
// Alert / LED+Buzzer
// ============================================================================
void ConfigManager::setAlertConfig(const AlertPattern::Config& a) {
    _cfg.alert = a;
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.putUChar ("al_en", a.alertEnabled  ? 1 : 0);
    _prefs.putUChar ("al_lw", a.ledWifiEnabled ? 1 : 0);
    _prefs.putUShort("al_bs", a.beepShortMs);
    _prefs.putUShort("al_bl", a.beepLongMs);
    _prefs.putUShort("al_bg", a.beepGapMs);
    _prefs.putUShort("al_db", a.debounceMs);
    _prefs.putUShort("al_mr", a.maxRangeMm);
    _prefs.end();
    Log::info(TAG_CONFIG, "Alert saved: en=%d ledWifi=%d short=%u long=%u gap=%u dbnc=%u range=%u",
              (int)a.alertEnabled, (int)a.ledWifiEnabled,
              a.beepShortMs, a.beepLongMs, a.beepGapMs, a.debounceMs, a.maxRangeMm);
}

// ============================================================================
// Reset
// ============================================================================
void ConfigManager::resetToDefaults() {
    _prefs.begin(NVS_NAMESPACE, false);
    _prefs.clear();
    _prefs.end();
    applyDefaults();
    Log::info(TAG_CONFIG, "Config reset to defaults");
}
