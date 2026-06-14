#include "web_server.h"
#include "mqtt_client.h"
#include "alert_pattern.h"
#include "logger.h"

#include <Update.h>      // ESP32 OTA helper (built-in to esp32 Arduino core)
#include <esp_ota_ops.h> // esp_ota_mark_app_invalid_rollback_and_reboot()

// Global instance
WebRadarServer webServer;

// Static callback needs access to instance
static WebRadarServer* _instance = nullptr;

void WebRadarServer::begin() {
    _instance = this;
    setupWiFi();
    setupMDNS();
    setupHTTP();
    setupWebSocket();

    // Start captive portal DNS in AP mode (incl. the rescue AP after a STA timeout)
    if (_isAP) {
        _dns.start(53, "*", WiFi.softAPIP());
        Log::info("Captive portal DNS started");
    }

    // Seed the link-state edge detector so maintainWifi() doesn't fire a spurious
    // "reconnected" event on the first loop iteration.
    _staWasConnected = (WiFi.status() == WL_CONNECTED);
    _ready = true;
}

void WebRadarServer::loop() {
    if (_isAP) _dns.processNextRequest();
    _ws.loop();
    _http.handleClient();
}

// ============================================================================
// WiFi Setup - uses ConfigManager for stored credentials
// ============================================================================
void WebRadarServer::setupWiFi() {
    const DeviceConfig& cfg = configManager.get();

    if (cfg.wifiMode == 1 && strlen(cfg.wifiSSID) > 0) {
        // Station mode - connect to user's WiFi
        WiFi.mode(WIFI_STA);
        WiFi.setHostname(cfg.deviceName);
        // Let the stack auto-rejoin on transient drops; maintainWifi() backstops it.
        WiFi.setAutoReconnect(cfg.autoReconnect != 0);
        WiFi.begin(cfg.wifiSSID, cfg.wifiPass);
        Log::info("Connecting to WiFi: %s", cfg.wifiSSID);

        uint32_t startMs = millis();
        uint32_t lastDot = startMs;
        while (WiFi.status() != WL_CONNECTED) {
            // Drive the GPIO26 indicator (1s/1s blink) while we wait.
            alertPattern.update();
            delay(10);
            if (millis() - lastDot >= 500) {
                Serial.print(".");
                lastDot = millis();
            }
            if (millis() - startMs > WIFI_STA_TIMEOUT) {
                Serial.println();
                if (cfg.autoReconnect) {
                    // "Auto Find WiFi": don't get stuck. Bring up a rescue AP for
                    // config access while STA keeps retrying in the background
                    // (maintainWifi() re-kicks the join and drops the AP on success).
                    Log::warn(TAG_WIFI, "WiFi timeout — AP+STA rescue, will keep retrying %s",
                              cfg.wifiSSID);
                    startRescueAp();
                    return;
                }
                Log::error("WiFi timeout! Falling back to AP mode.");
                // Fall through to AP mode below
                goto start_ap;
            }
        }
        Serial.println();
        _ip = WiFi.localIP().toString();
        Log::info("WiFi connected! IP: %s", _ip.c_str());
        return;
    }

start_ap:
    // Access Point mode (default or fallback)
    WiFi.mode(WIFI_AP);
    WiFi.softAP(WIFI_AP_SSID, WIFI_AP_PASS, WIFI_AP_CHANNEL, 0, WIFI_AP_MAX_CONN);
    delay(100);
    _isAP = true;
    _ip = WiFi.softAPIP().toString();
    Log::info("WiFi AP started: %s (pass: %s)", WIFI_AP_SSID, WIFI_AP_PASS);
    Log::info("IP: %s", _ip.c_str());
}

// ============================================================================
// Rescue AP: AP + STA at once. The "HumanRadar" AP stays reachable for config
// while the STA interface keeps trying to (re)join the configured network.
// ============================================================================
void WebRadarServer::startRescueAp() {
    const DeviceConfig& cfg = configManager.get();
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(WIFI_AP_SSID, WIFI_AP_PASS, WIFI_AP_CHANNEL, 0, WIFI_AP_MAX_CONN);
    delay(100);
    // (Re)start the background STA join — switching mode can reset the prior attempt.
    WiFi.begin(cfg.wifiSSID, cfg.wifiPass);
    _isAP = true;
    _ip = WiFi.softAPIP().toString();
    _lastStaRetryMs = millis();
    Log::info("Rescue AP up: %s (%s) — STA retrying %s",
              WIFI_AP_SSID, _ip.c_str(), cfg.wifiSSID);
}

// ============================================================================
// WiFi keep-alive (called every loop). Replaces the old static monitorWifi():
//   * edge-detects STA up/down for the LED/buzzer indicator
//   * when "Auto Find WiFi" is on: brings up a rescue AP on drop, re-kicks the
//     STA join periodically, and drops the rescue AP once STA is back.
// ============================================================================
void WebRadarServer::maintainWifi() {
    const uint32_t now = millis();
    if (now - _lastWifiCheckMs < 1000) return;
    _lastWifiCheckMs = now;

    const DeviceConfig& cfg = configManager.get();
    // User explicitly chose AP-only mode — nothing to maintain.
    if (cfg.wifiMode != 1) return;

    const bool connected = (WiFi.status() == WL_CONNECTED);

    // Edge-triggered indicator callbacks (formerly monitorWifi()).
    if (connected != _staWasConnected) {
        if (connected) {
            Log::info("WiFi reconnected");
            alertPattern.onWifiConnected();
        } else {
            Log::warn(TAG_WIFI, "WiFi link lost");
            alertPattern.onWifiDisconnected();
        }
        _staWasConnected = connected;
    }

    // Feature off → detect-only, like the original behaviour.
    if (!cfg.autoReconnect) return;

    if (connected) {
        // Back online. If a rescue AP was up, drop it and return to plain STA.
        if (_isAP) {
            Log::info("STA reconnected — dropping rescue AP");
            _dns.stop();
            WiFi.softAPdisconnect(true);
            WiFi.mode(WIFI_STA);   // back to plain STA: tears down only the AP iface, keeps STA link
            _isAP = false;
        }
        _ip = WiFi.localIP().toString();
        return;
    }

    // Disconnected: make sure the rescue AP is up so config stays reachable.
    if (!_isAP) {
        Log::warn(TAG_WIFI, "STA down — bringing up rescue AP, retrying in background");
        startRescueAp();
        _dns.start(53, "*", WiFi.softAPIP());
    }

    // Periodically re-kick the STA join in case the stack stopped trying.
    if (now - _lastStaRetryMs >= WIFI_STA_RETRY_INTERVAL) {
        _lastStaRetryMs = now;
        Log::info("Retrying STA join to %s", cfg.wifiSSID);
        WiFi.begin(cfg.wifiSSID, cfg.wifiPass);
    }
}

// ============================================================================
// mDNS - access via http://humanradar.local
// ============================================================================
void WebRadarServer::setupMDNS() {
    const DeviceConfig& cfg = configManager.get();
    // Use device name as mDNS hostname, fallback to "humanradar"
    String hostname = String(cfg.deviceName);
    hostname.toLowerCase();
    hostname.replace(" ", "");

    if (MDNS.begin(hostname.c_str())) {
        MDNS.addService("http", "tcp", WEB_SERVER_PORT);
        // Custom service type the Android app's NsdManager scans for. Includes
        // TXT records carrying the original mixed-case device name, fw, and
        // the radar WebSocket path so the HybridTransportManager can connect
        // without round-tripping through MQTT first.
        MDNS.addService("humanradar", "tcp", WEBSOCKET_PORT);
        MDNS.addServiceTxt("humanradar", "tcp", "name", cfg.deviceName);
        MDNS.addServiceTxt("humanradar", "tcp", "fw",   FW_VERSION);
        MDNS.addServiceTxt("humanradar", "tcp", "path", "/");
        MDNS.addServiceTxt("humanradar", "tcp", "ws",   String(WEBSOCKET_PORT));
        MDNS.addServiceTxt("humanradar", "tcp", "http", String(WEB_SERVER_PORT));
        Log::info("mDNS: http://%s.local  +  _humanradar._tcp.local:%d",
                  hostname.c_str(), WEBSOCKET_PORT);
    } else {
        Log::error("mDNS failed to start");
    }
}

// ============================================================================
// HTTP Server
// ============================================================================
void WebRadarServer::setupHTTP() {
    // Radar page
    _http.on("/", HTTP_GET, [this]() {
        _http.send_P(200, "text/html", RADAR_HTML);
    });

    // Settings page
    _http.on("/settings", HTTP_GET, [this]() {
        _http.send_P(200, "text/html", SETTINGS_HTML);
    });

    // API: Get config
    _http.on("/api/config", HTTP_GET, [this]() {
        handleGetConfig();
    });

    // API: Save config
    _http.on("/api/config", HTTP_POST, [this]() {
        handleSaveConfig();
    });

    // API: Reset to defaults
    _http.on("/api/reset", HTTP_POST, [this]() {
        handleResetConfig();
    });

    // API: Alert / LED+Buzzer (GPIO26)
    _http.on("/api/alert", HTTP_GET, [this]() {
        handleGetAlert();
    });
    _http.on("/api/alert", HTTP_POST, [this]() {
        handleSaveAlert();
    });
    _http.on("/api/alert/test", HTTP_POST, [this]() {
        handleTestAlert();
    });

    // OTA firmware update endpoint:
    //   POST /api/firmware-update  (multipart/form-data, field name "firmware")
    // Receives the .bin file in chunks and streams it into the OTA partition via
    // ESP32 Update.h. On success, the response fires and the device reboots into
    // the new firmware automatically.
    _http.on("/api/firmware-update", HTTP_POST,
        [this]() {
            // This first lambda runs AFTER the upload completes (or fails).
            if (Update.hasError()) {
                String err = String("{\"ok\":false,\"error\":\"") +
                             Update.errorString() + "\"}";
                Log::error(TAG_SYSTEM, "OTA failed: %s", Update.errorString());
                _http.send(500, "application/json", err);
                alertPattern.update();
            } else {
                _http.send(200, "application/json",
                           "{\"ok\":true,\"restarting\":true}");
                Log::info(TAG_SYSTEM,
                          "OTA complete, playing finish pattern then restart");
                alertPattern.onFirmwareUpdateFinish();
                uint32_t finishDeadline = millis() + 2500;  // 2s pattern + slack
                while (alertPattern.isFirmwareUpdateActive()
                        && (int32_t)(millis() - finishDeadline) < 0) {
                    alertPattern.update();
                    delay(10);
                }
                ESP.restart();
            }
        },
        [this]() {
            // This second lambda runs per upload chunk.
            HTTPUpload& upload = _http.upload();
            switch (upload.status) {
                case UPLOAD_FILE_START:
                    Log::info(TAG_SYSTEM, "OTA start: %s", upload.filename.c_str());
                    // Indefinite 1s ON / 1s OFF heartbeat on GPIO26 for the
                    // entire upload — switched to the 4× 250 ms finish
                    // pattern only after Update.end() succeeds.
                    alertPattern.onFirmwareUpdateStart();
                    alertPattern.update();
                    // Start an OTA write to the "next" partition. Size unknown =
                    // accept whatever fits the partition.
                    if (!Update.begin(UPDATE_SIZE_UNKNOWN)) {
                        Log::error(TAG_SYSTEM, "Update.begin failed: %s",
                                   Update.errorString());
                    }
                    break;
                case UPLOAD_FILE_WRITE:
                    if (Update.write(upload.buf, upload.currentSize) !=
                        upload.currentSize) {
                        Log::error(TAG_SYSTEM, "Update.write failed: %s",
                                   Update.errorString());
                    }
                    // Drive the indicator from inside the upload loop because
                    // main loop() is paused while the chunked POST drains.
                    alertPattern.update();
                    break;
                case UPLOAD_FILE_END:
                    if (Update.end(true)) {
                        Log::info(TAG_SYSTEM, "OTA end: %u bytes accepted",
                                  upload.totalSize);
                    } else {
                        Log::error(TAG_SYSTEM, "Update.end failed: %s",
                                   Update.errorString());
                    }
                    alertPattern.update();
                    break;
                case UPLOAD_FILE_ABORTED:
                    Update.end();
                    Log::warn(TAG_SYSTEM, "OTA aborted by client");
                    alertPattern.update();
                    break;
                default:
                    break;
            }
        });

    // Health check
    _http.on("/health", HTTP_GET, [this]() {
        _http.send(200, "application/json", "{\"status\":\"ok\"}");
    });

    // Lightweight firmware version endpoint — the app polls this to compare
    // against the manifest in GitHub Releases.
    _http.on("/api/version", HTTP_GET, [this]() {
        char json[128];
        snprintf(json, sizeof(json), "{\"fw\":\"%s\"}", FW_VERSION);
        _http.send(200, "application/json", json);
    });

    // Richer system info for pre-upload OTA integrity check.
    _http.on("/api/info", HTTP_GET, [this]() {
        const esp_partition_t* nextPart = esp_ota_get_next_update_partition(nullptr);
        const esp_partition_t* runPart  = esp_ota_get_running_partition();
        const DeviceConfig& cfg = configManager.get();
        String md5 = ESP.getSketchMD5();
        char json[512];
        snprintf(json, sizeof(json),
            "{\"fw\":\"%s\",\"ip\":\"%s\",\"mac\":\"%s\",\"name\":\"%s\","
            "\"free_app_partition_bytes\":%u,\"running_partition_bytes\":%u,"
            "\"sketch_size\":%u,\"sketch_md5\":\"%s\","
            "\"free_heap\":%u,\"total_heap\":%u}",
            FW_VERSION,
            _ip.c_str(),
            WiFi.macAddress().c_str(),
            strlen(cfg.deviceName) > 0 ? cfg.deviceName : "HumanRadar",
            nextPart ? (unsigned)nextPart->size : 0u,
            runPart  ? (unsigned)runPart->size  : 0u,
            (unsigned)ESP.getSketchSize(),
            md5.c_str(),
            (unsigned)ESP.getFreeHeap(),
            (unsigned)ESP.getHeapSize());
        _http.send(200, "application/json", json);
    });

    // Battery monitor — assumes a voltage divider on GPIO34 (input-only). If
    // the user has no divider wired the reading is meaningless but the
    // endpoint stays useful for diagnostics ("can the chip read this pin?").
    _http.on("/api/battery", HTTP_GET, [this]() {
        const int BATTERY_PIN = 34;
        int raw = analogRead(BATTERY_PIN);
        // 12-bit ADC, 3.3V ref, assume 2:1 divider (most common Li-ion setups).
        float voltage = (raw / 4095.0f) * 3.3f * 2.0f;
        char json[128];
        snprintf(json, sizeof(json),
                 "{\"pin\":%d,\"raw\":%d,\"voltage\":%.2f}",
                 BATTERY_PIN, raw, voltage);
        _http.send(200, "application/json", json);
    });

    // Rollback to the previous OTA partition. The bootloader keeps the old
    // app image until we explicitly mark the new one as valid; calling
    // esp_ota_mark_app_invalid_rollback_and_reboot() reboots into the prior
    // partition. Used by Zone E2 in the app.
    _http.on("/api/firmware-rollback", HTTP_POST, [this]() {
        Log::warn(TAG_SYSTEM, "Firmware rollback requested via /api/firmware-rollback");
        _http.send(200, "application/json", "{\"ok\":true,\"restarting\":true}");
        delay(500);
        esp_ota_mark_app_invalid_rollback_and_reboot();
        // If we get here, rollback failed — fall back to plain restart.
        ESP.restart();
    });

    // Captive portal: redirect unknown URLs to /settings in AP mode
    _http.onNotFound([this]() {
        if (_isAP) {
            _http.sendHeader("Location", "http://" + _ip + "/settings", true);
            _http.send(302, "text/plain", "Redirecting to settings...");
        } else {
            _http.send(404, "text/plain", "Not found");
        }
    });

    _http.begin();
    Log::info("HTTP server on port %d", WEB_SERVER_PORT);
}

// ============================================================================
// API: Get Config (JSON)
// ============================================================================
void WebRadarServer::handleGetConfig() {
    const DeviceConfig& cfg = configManager.get();

    char json[1200];   // headroom for all max-length string fields + zones + ar/fw
    int len = snprintf(json, sizeof(json),
        "{\"wm\":%d,\"ws\":\"%s\",\"wp\":\"%s\","
        "\"me\":%d,\"mr\":%d,\"mh\":\"%s\",\"mp\":%d,\"mu\":\"%s\",\"mpp\":\"%s\",\"ms\":\"%s\","
        "\"dn\":\"%s\",\"ip\":\"%s\",\"ar\":%d,"
        "\"pi\":%d,\"ud\":%d,\"tt\":%d,\"mt\":%d,\"sn\":%d,"
        "\"z0\":{\"en\":%d,\"x1\":%d,\"y1\":%d,\"x2\":%d,\"y2\":%d},"
        "\"z1\":{\"en\":%d,\"x1\":%d,\"y1\":%d,\"x2\":%d,\"y2\":%d},"
        "\"z2\":{\"en\":%d,\"x1\":%d,\"y1\":%d,\"x2\":%d,\"y2\":%d},"
        "\"fw\":\"%s\"}",
        cfg.wifiMode, cfg.wifiSSID, cfg.wifiPass,
        cfg.mqttEnabled, cfg.mqttProto,
        cfg.mqttHost, cfg.mqttPort, cfg.mqttUser, cfg.mqttPass,
        mqttClient.getStatusText(),
        cfg.deviceName, _ip.c_str(), cfg.autoReconnect,
        cfg.publishIntervalMs, cfg.unmannedDelayMs, cfg.targetTimeoutMs,
        cfg.multiTargetMode, cfg.sensitivity,
        cfg.zones[0].enabled, cfg.zones[0].x1, cfg.zones[0].y1, cfg.zones[0].x2, cfg.zones[0].y2,
        cfg.zones[1].enabled, cfg.zones[1].x1, cfg.zones[1].y1, cfg.zones[1].x2, cfg.zones[1].y2,
        cfg.zones[2].enabled, cfg.zones[2].x1, cfg.zones[2].y1, cfg.zones[2].x2, cfg.zones[2].y2,
        FW_VERSION
    );

    _http.send(200, "application/json", json);
}

// ============================================================================
// API: Save Config (JSON POST)
// ============================================================================
void WebRadarServer::handleSaveConfig() {
    if (!_http.hasArg("plain")) {
        _http.send(400, "application/json", "{\"ok\":false,\"error\":\"No body\"}");
        return;
    }

    String body = _http.arg("plain");
    Log::info("Save config: %s", body.c_str());

    // Simple JSON parsing (no library needed for flat structure)
    // Extract values using indexOf/substring
    auto getJsonStr = [&](const char* key) -> String {
        String search = String("\"") + key + "\":\"";
        int start = body.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = body.indexOf("\"", start);
        if (end < 0) return "";
        return body.substring(start, end);
    };

    auto getJsonInt = [&](const char* key, int def) -> int {
        String search = String("\"") + key + "\":";
        int start = body.indexOf(search);
        if (start < 0) return def;
        start += search.length();
        return body.substring(start).toInt();
    };

    // hasKey() — tells the partial-update logic below whether a key was actually
    // present in the body. Without this guard a POST that only carries WiFi keys
    // would wipe MQTT credentials (and vice versa) because the missing keys are
    // indistinguishable from explicit zero/empty values otherwise.
    auto hasKey = [&](const char* key) -> bool {
        return body.indexOf(String("\"") + key + "\":") >= 0;
    };

    // Save WiFi — only if at least one wifi key is present.
    if (hasKey("wm") || hasKey("ws") || hasKey("wp")) {
        const DeviceConfig& cur = configManager.get();
        uint8_t wm = hasKey("wm") ? (uint8_t)getJsonInt("wm", cur.wifiMode) : cur.wifiMode;
        String  ws = hasKey("ws") ? getJsonStr("ws") : String(cur.wifiSSID);
        String  wp = hasKey("wp") ? getJsonStr("wp") : String(cur.wifiPass);
        configManager.setWiFi(wm, ws.c_str(), wp.c_str());
    }

    // Save "Auto Find WiFi" toggle (auto-reconnect + AP rescue).
    if (hasKey("ar")) {
        int ar = getJsonInt("ar", -1);
        if (ar >= 0) configManager.setAutoReconnect((uint8_t)ar);
    }

    // Save MQTT — only if at least one mqtt key is present.
    if (hasKey("me") || hasKey("mr") || hasKey("mh") || hasKey("mp")
            || hasKey("mu") || hasKey("mpp")) {
        const DeviceConfig& cur = configManager.get();
        uint8_t me = hasKey("me") ? (uint8_t)getJsonInt("me", cur.mqttEnabled) : cur.mqttEnabled;
        uint8_t mr = hasKey("mr") ? (uint8_t)getJsonInt("mr", cur.mqttProto)   : cur.mqttProto;
        String  mh = hasKey("mh") ? getJsonStr("mh") : String(cur.mqttHost);
        int     mp = hasKey("mp") ? getJsonInt("mp", cur.mqttPort)             : cur.mqttPort;
        String  mu = hasKey("mu") ? getJsonStr("mu") : String(cur.mqttUser);
        String  mpp= hasKey("mpp") ? getJsonStr("mpp") : String(cur.mqttPass);
        configManager.setMQTT(me, mr, mh.c_str(), (uint16_t)mp, mu.c_str(), mpp.c_str());
    }

    // Save device name
    if (hasKey("dn")) {
        String dn = getJsonStr("dn");
        if (dn.length() > 0) {
            configManager.setDeviceName(dn.c_str());
        }
    }

    // Save sensor config — these guards already existed (-1 sentinel) so they
    // tolerated partial bodies, but switch to hasKey() for consistency.
    if (hasKey("pi")) {
        int pi = getJsonInt("pi", -1);
        if (pi > 0) configManager.setPublishInterval((uint16_t)pi);
    }
    if (hasKey("ud")) {
        int ud = getJsonInt("ud", -1);
        if (ud > 0) configManager.setUnmannedDelay((uint16_t)ud);
    }
    if (hasKey("tt")) {
        int tt = getJsonInt("tt", -1);
        if (tt > 0) configManager.setTargetTimeout((uint16_t)tt);
    }
    if (hasKey("mt")) {
        int mt = getJsonInt("mt", -1);
        if (mt >= 0) configManager.setMultiTargetMode((uint8_t)mt);
    }
    if (hasKey("sn")) {
        int sn = getJsonInt("sn", -1);
        if (sn >= 0) configManager.setSensitivity((uint8_t)sn);
    }

    // Detection zones — flat-key shape ("z0_en", "z0_x1", ...). The Android
    // DetectionZonesDialog sends both nested (z0:{...}) and flat formats
    // for forward compatibility; we parse flat because the indexOf-based
    // JSON helper above can't descend into nested objects cleanly.
    char zkey[12];
    for (int i = 0; i < 3; i++) {
        snprintf(zkey, sizeof(zkey), "z%d_en", i);
        bool present = hasKey(zkey);
        if (!present) {
            for (const char* suffix : { "_x1", "_y1", "_x2", "_y2" }) {
                snprintf(zkey, sizeof(zkey), "z%d%s", i, suffix);
                if (hasKey(zkey)) { present = true; break; }
            }
        }
        if (!present) continue;

        const DetectionZone& cur = configManager.get().zones[i];
        snprintf(zkey, sizeof(zkey), "z%d_en", i);
        bool en = hasKey(zkey) ? (getJsonInt(zkey, cur.enabled ? 1 : 0) != 0)
                               : cur.enabled;
        snprintf(zkey, sizeof(zkey), "z%d_x1", i);
        int x1 = hasKey(zkey) ? getJsonInt(zkey, cur.x1) : cur.x1;
        snprintf(zkey, sizeof(zkey), "z%d_y1", i);
        int y1 = hasKey(zkey) ? getJsonInt(zkey, cur.y1) : cur.y1;
        snprintf(zkey, sizeof(zkey), "z%d_x2", i);
        int x2 = hasKey(zkey) ? getJsonInt(zkey, cur.x2) : cur.x2;
        snprintf(zkey, sizeof(zkey), "z%d_y2", i);
        int y2 = hasKey(zkey) ? getJsonInt(zkey, cur.y2) : cur.y2;

        auto clamp16 = [](int v) -> int16_t {
            if (v >  32767) return  32767;
            if (v < -32768) return -32768;
            return (int16_t)v;
        };
        configManager.setZone(i, en,
            clamp16(x1), clamp16(y1), clamp16(x2), clamp16(y2));
        Log::info("Zone %d updated: en=%d (%d,%d)→(%d,%d)",
                  i, en ? 1 : 0, x1, y1, x2, y2);
    }

    _http.send(200, "application/json", "{\"ok\":true}");

    // Restart after short delay to apply new WiFi settings
    Log::info("Restarting in 2 seconds to apply settings...");
    delay(2000);
    ESP.restart();
}

// ============================================================================
// API: Reset to Defaults
// ============================================================================
void WebRadarServer::handleResetConfig() {
    configManager.resetToDefaults();
    _http.send(200, "application/json", "{\"ok\":true}");

    Log::info("Restarting in 2 seconds...");
    delay(2000);
    ESP.restart();
}

// ============================================================================
// API: Alert / LED+Buzzer (GPIO26)
// ============================================================================
void WebRadarServer::handleGetAlert() {
    const auto& a = configManager.getAlertConfig();
    char json[256];
    int len = snprintf(json, sizeof(json),
        "{\"ae\":%d,\"le\":%d,\"bs\":%u,\"bl\":%u,\"bg\":%u,\"db\":%u,\"mr\":%u}",
        (int)a.alertEnabled, (int)a.ledWifiEnabled,
        a.beepShortMs, a.beepLongMs, a.beepGapMs, a.debounceMs, a.maxRangeMm);
    (void)len;
    _http.send(200, "application/json", json);
}

void WebRadarServer::handleSaveAlert() {
    if (!_http.hasArg("plain")) {
        _http.send(400, "application/json", "{\"ok\":false,\"error\":\"No body\"}");
        return;
    }
    const String body = _http.arg("plain");

    auto getInt = [&](const char* key, int def) -> int {
        String search = String("\"") + key + "\":";
        int start = body.indexOf(search);
        if (start < 0) return def;
        start += search.length();
        // skip whitespace
        while (start < (int)body.length() && (body[start] == ' ' || body[start] == '\t')) start++;
        return body.substring(start).toInt();
    };

    AlertPattern::Config a = configManager.getAlertConfig();
    a.alertEnabled   = getInt("ae", a.alertEnabled  ? 1 : 0) != 0;
    a.ledWifiEnabled = getInt("le", a.ledWifiEnabled ? 1 : 0) != 0;
    a.beepShortMs    = (uint16_t)constrain(getInt("bs", a.beepShortMs), 50, 5000);
    a.beepLongMs     = (uint16_t)constrain(getInt("bl", a.beepLongMs),  100, 10000);
    a.beepGapMs      = (uint16_t)constrain(getInt("bg", a.beepGapMs),   50, 5000);
    a.debounceMs     = (uint16_t)constrain(getInt("db", a.debounceMs),  0,  10000);
    a.maxRangeMm     = (uint16_t)constrain(getInt("mr", a.maxRangeMm),  0,  20000);

    configManager.setAlertConfig(a);
    alertPattern.setConfig(a);

    _http.send(200, "application/json", "{\"ok\":true}");
}

void WebRadarServer::handleTestAlert() {
    int shorts = 1;
    int useLong = 0;
    if (_http.hasArg("plain")) {
        const String body = _http.arg("plain");
        auto getInt = [&](const char* key, int def) -> int {
            String search = String("\"") + key + "\":";
            int start = body.indexOf(search);
            if (start < 0) return def;
            start += search.length();
            while (start < (int)body.length() && (body[start] == ' ' || body[start] == '\t')) start++;
            return body.substring(start).toInt();
        };
        shorts  = constrain(getInt("count", 1), 0, 10);
        useLong = getInt("long", 0);
    }
    alertPattern.triggerTest((uint8_t)shorts, useLong != 0);
    _http.send(200, "application/json", "{\"ok\":true}");
}

// ============================================================================
// WebSocket Server
// ============================================================================
void WebRadarServer::setupWebSocket() {
    _ws.begin();
    _ws.onEvent(onWebSocketEvent);
    Log::info("WebSocket server on port %d", WEBSOCKET_PORT);
}

void WebRadarServer::onWebSocketEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
    if (!_instance) return;

    switch (type) {
    case WStype_CONNECTED:
        _instance->_clientCount++;
        Log::info("WS client #%d connected (total: %d)", num, _instance->_clientCount);
        break;

    case WStype_DISCONNECTED:
        if (_instance->_clientCount > 0) _instance->_clientCount--;
        Log::info("WS client #%d disconnected (total: %d)", num, _instance->_clientCount);
        break;

    default:
        break;
    }
}

// ============================================================================
// Broadcast radar frame as JSON to all WebSocket clients
// ============================================================================
void WebRadarServer::broadcastFrame(const RadarFrame& frame, uint32_t frameCount, uint32_t errorCount) {
    if (_clientCount == 0) return;

    char json[512];
    int len = snprintf(json, sizeof(json),
        "{\"t\":["
        "{\"x\":%d,\"y\":%d,\"s\":%d,\"d\":%u,\"a\":%.1f,\"p\":%s},"
        "{\"x\":%d,\"y\":%d,\"s\":%d,\"d\":%u,\"a\":%.1f,\"p\":%s},"
        "{\"x\":%d,\"y\":%d,\"s\":%d,\"d\":%u,\"a\":%.1f,\"p\":%s}"
        "],\"fc\":%lu,\"ec\":%lu}",
        frame.targets[0].x, frame.targets[0].y, frame.targets[0].speed,
        frame.targets[0].distance, frame.targets[0].angle,
        frame.targets[0].present ? "true" : "false",

        frame.targets[1].x, frame.targets[1].y, frame.targets[1].speed,
        frame.targets[1].distance, frame.targets[1].angle,
        frame.targets[1].present ? "true" : "false",

        frame.targets[2].x, frame.targets[2].y, frame.targets[2].speed,
        frame.targets[2].distance, frame.targets[2].angle,
        frame.targets[2].present ? "true" : "false",

        frameCount, errorCount
    );

    _ws.broadcastTXT(json, len);
}
