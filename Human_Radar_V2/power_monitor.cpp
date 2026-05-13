#include "power_monitor.h"

#include <Preferences.h>
#include <math.h>

#include "logger.h"

// Arduino-ESP32 exposes temperatureRead() for the internal die sensor.
// Declared here to avoid pulling in a deprecation-warning header.
extern "C" {
float temperatureRead();
}

namespace {
const char* NS = "pwrmon";
const char* K_BROWNOUT = "bo";
const char* K_PANIC    = "pa";
const char* K_BOOTS    = "bt";
const char* TAG_PWR    = "PWR";

esp_reset_reason_t g_bootReason = ESP_RST_UNKNOWN;

uint32_t readU32(const char* key) {
    Preferences p;
    p.begin(NS, true);
    uint32_t v = p.getUInt(key, 0);
    p.end();
    return v;
}

void writeU32(const char* key, uint32_t v) {
    Preferences p;
    p.begin(NS, false);
    p.putUInt(key, v);
    p.end();
}

// Heuristic score. 100 = healthy supply. Penalties stack:
//   -40 if last reset was a brown-out
//   -25 if last reset was a panic / watchdog / unknown
//   -1  per lifetime brown-out (capped at -30)
//   -1  per lifetime panic (capped at -15)
//   -10 if die temp > 70 °C (sustained high load can mask a marginal PSU)
//   -20 if die temp > 80 °C
// Level: 0 OK >=70, 1 Warning 40-69, 2 Critical <40 or last_reset==brownout.
void deriveHealth(PowerMonitor::Snapshot& s) {
    int score = 100;
    if (s.resetReason == ESP_RST_BROWNOUT) score -= 40;
    else if (s.resetReason == ESP_RST_PANIC
          || s.resetReason == ESP_RST_TASK_WDT
          || s.resetReason == ESP_RST_INT_WDT
          || s.resetReason == ESP_RST_WDT
          || s.resetReason == ESP_RST_UNKNOWN) score -= 25;

    uint32_t boPenalty = s.brownoutCountTotal;
    if (boPenalty > 30) boPenalty = 30;
    score -= (int)boPenalty;

    uint32_t paPenalty = s.panicCountTotal;
    if (paPenalty > 15) paPenalty = 15;
    score -= (int)paPenalty;

    if (!isnan(s.dieTempC)) {
        if (s.dieTempC > 80.0f) score -= 20;
        else if (s.dieTempC > 70.0f) score -= 10;
    }

    if (score < 0)   score = 0;
    if (score > 100) score = 100;
    s.healthScore = (uint8_t)score;

    if (s.resetReason == ESP_RST_BROWNOUT || score < 40) s.healthLevel = 2;
    else if (score < 70) s.healthLevel = 1;
    else s.healthLevel = 0;
}

} // namespace

namespace PowerMonitor {

void begin() {
    g_bootReason = esp_reset_reason();

    uint32_t boots = readU32(K_BOOTS) + 1;
    writeU32(K_BOOTS, boots);

    if (g_bootReason == ESP_RST_BROWNOUT) {
        uint32_t bo = readU32(K_BROWNOUT) + 1;
        writeU32(K_BROWNOUT, bo);
        Log::warn(TAG_PWR, "Brown-out reset detected (lifetime=%u)", bo);
    } else if (g_bootReason == ESP_RST_PANIC
            || g_bootReason == ESP_RST_TASK_WDT
            || g_bootReason == ESP_RST_INT_WDT
            || g_bootReason == ESP_RST_WDT) {
        uint32_t pa = readU32(K_PANIC) + 1;
        writeU32(K_PANIC, pa);
        Log::warn(TAG_PWR, "Panic/WDT reset detected (lifetime=%u, reason=%d)",
                  pa, (int)g_bootReason);
    } else {
        Log::info(TAG_PWR, "Boot %u (reason=%d)", boots, (int)g_bootReason);
    }
}

Snapshot snapshot() {
    Snapshot s{};
    s.resetReason        = (uint8_t)g_bootReason;
    s.brownoutCountTotal = readU32(K_BROWNOUT);
    s.panicCountTotal    = readU32(K_PANIC);
    s.bootCount          = readU32(K_BOOTS);
    s.uptimeMs           = millis();

    // temperatureRead returns °C; some SDKs return a sentinel ~53.33 on
    // unsupported parts. Accept any value; downstream tolerates NaN.
    float t = temperatureRead();
    s.dieTempC = isnan(t) ? NAN : t;

    deriveHealth(s);
    return s;
}

void resetCounters() {
    Preferences p;
    p.begin(NS, false);
    p.clear();
    p.end();
    Log::info(TAG_PWR, "Power counters cleared");
}

} // namespace PowerMonitor
