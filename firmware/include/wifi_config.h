#ifndef WIFI_CONFIG_H
#define WIFI_CONFIG_H

// ============================================================================
// WiFi Configuration
// ============================================================================
// Mode: Access Point (AP) - ESP32 creates its own WiFi network
// No router needed. Connect your phone/PC directly to ESP32.
//
// To switch to Station mode (connect to your home WiFi), change:
//   USE_AP_MODE -> false
//   WIFI_STA_SSID / WIFI_STA_PASS -> your WiFi credentials
// ============================================================================

// --- Access Point Mode (default) ---
#define USE_AP_MODE         true
#define WIFI_AP_SSID         "HumanRadar"
#define WIFI_AP_PASS         "radar1234"    // min 8 chars, or "" for open
#define WIFI_AP_CHANNEL      1
#define WIFI_AP_MAX_CONN     4

// --- Station Mode (connect to existing WiFi) ---
#define WIFI_STA_SSID        "YourWiFi"
#define WIFI_STA_PASS        "YourPassword"
#define WIFI_STA_TIMEOUT     10000          // ms, connection timeout

// --- Web Server ---
#define WEB_SERVER_PORT      80
#define WEBSOCKET_PORT       81

#endif // WIFI_CONFIG_H
