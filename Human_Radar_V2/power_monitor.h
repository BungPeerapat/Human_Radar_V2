#ifndef POWER_MONITOR_H
#define POWER_MONITOR_H

#include <Arduino.h>
#include <esp_system.h>

// PowerMonitor — software-only power health proxy for ESP32-WROOM-32.
//
// The WROOM-32 has no on-chip VCC/VBAT sense, so this module derives a
// best-effort health score from symptoms an undervolted board emits:
//   - brown-out resets (ESP_RST_BROWNOUT)
//   - panic / unknown / watchdog resets (often follow a sag)
//   - die temperature trend
//
// Counters live in NVS namespace "pwrmon" so they survive deep-sleep,
// reflash with preserve-data, and the LWT cycle. Reading is O(1) and
// piggybacks on the existing MQTT `health` command — no extra wiring.
namespace PowerMonitor {

struct Snapshot {
    uint8_t  resetReason;        // esp_reset_reason_t cast to uint8_t
    uint32_t brownoutCountTotal; // lifetime brown-outs (since reset_to_defaults)
    uint32_t panicCountTotal;    // lifetime panic / watchdog resets
    uint32_t bootCount;          // total boots (any cause)
    float    dieTempC;           // current die temperature, NaN if read fails
    uint32_t uptimeMs;           // millis() at snapshot time
    // Health score derived from the above. 0..100 (higher is better).
    uint8_t  healthScore;
    // 0 = OK, 1 = Warning, 2 = Critical
    uint8_t  healthLevel;
};

// Call once early in setup(), before WiFi/MQTT init. Reads the reset
// reason from esp_reset_reason(), updates the NVS counters, and stashes
// the boot reason for later snapshot calls.
void begin();

// Build a fresh snapshot for the `health` MQTT command.
Snapshot snapshot();

// Lifetime counter reset. Wired into ConfigManager::resetToDefaults so a
// factory reset starts the brown-out history fresh.
void resetCounters();

} // namespace PowerMonitor

#endif // POWER_MONITOR_H
