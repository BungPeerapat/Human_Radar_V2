#ifndef LOGGER_H
#define LOGGER_H

#include <Arduino.h>
#include "radar_types.h"

// ============================================================================
// Log Levels & Tags
// ============================================================================
enum LogLevel : uint8_t {
    LOG_DEBUG = 0,
    LOG_INFO  = 1,
    LOG_WARN  = 2,
    LOG_ERROR = 3,
    LOG_RADAR = 4,   // special: sensor data events
    LOG_LEVEL_COUNT = 5
};

static const char* LOG_LEVEL_NAMES[] = {"DEBUG", "INFO", "WARN", "ERROR", "RADAR"};

// Module tags
static const char* TAG_SYSTEM = "SYSTEM";
static const char* TAG_WIFI   = "WIFI";
static const char* TAG_MQTT   = "MQTT";
static const char* TAG_SENSOR = "SENSOR";
static const char* TAG_CONFIG = "CONFIG";
static const char* TAG_WEB    = "WEB";

// ============================================================================
// Log Entry (stored in circular buffer)
// ============================================================================
struct LogEntry {
    uint32_t timestamp;       // millis()
    uint8_t  level;           // LogLevel
    char     tag[8];          // module tag
    char     message[128];    // log message
    uint32_t freeHeap;        // ESP.getFreeHeap()
    uint32_t uptime;          // millis()
};

// ============================================================================
// Circular Log Buffer
// ============================================================================
static constexpr uint16_t LOG_BUFFER_SIZE = 100;

// ============================================================================
// Logger: Enhanced logging with levels, tags, circular buffer, MQTT forwarding
// ============================================================================

// Forward declaration - MQTT log publish callback
typedef void (*LogMqttPublishFn)(const LogEntry& entry);

namespace Log {

// ---- State ----
inline LogLevel       _minLevel = LOG_INFO;
inline LogLevel       _mqttMinLevel = LOG_INFO;
inline LogEntry       _buffer[LOG_BUFFER_SIZE];
inline uint16_t       _bufHead = 0;
inline uint16_t       _bufCount = 0;
inline LogMqttPublishFn _mqttPublishFn = nullptr;
inline bool           _mqttForwardEnabled = false;

// ---- Config ----
inline void setMinLevel(LogLevel level) { _minLevel = level; }
inline LogLevel getMinLevel() { return _minLevel; }
inline void setMqttMinLevel(LogLevel level) { _mqttMinLevel = level; }
inline void setMqttPublisher(LogMqttPublishFn fn) { _mqttPublishFn = fn; }
inline void enableMqttForward(bool en) { _mqttForwardEnabled = en; }

// ---- Buffer access ----
inline uint16_t getBufferCount() { return _bufCount; }

inline const LogEntry& getBufferEntry(uint16_t index) {
    uint16_t start;
    if (_bufCount < LOG_BUFFER_SIZE) {
        start = 0;
    } else {
        start = _bufHead;
    }
    uint16_t realIdx = (start + index) % LOG_BUFFER_SIZE;
    return _buffer[realIdx];
}

// ---- Core log function ----
inline void log(LogLevel level, const char* tag, const char* fmt, va_list args) {
    if (level < _minLevel) return;

    LogEntry entry;
    entry.timestamp = millis();
    entry.level = level;
    entry.freeHeap = ESP.getFreeHeap();
    entry.uptime = millis();
    strlcpy(entry.tag, tag, sizeof(entry.tag));
    vsnprintf(entry.message, sizeof(entry.message), fmt, args);

    _buffer[_bufHead] = entry;
    _bufHead = (_bufHead + 1) % LOG_BUFFER_SIZE;
    if (_bufCount < LOG_BUFFER_SIZE) _bufCount++;

    const char* lvlStr = (level < LOG_LEVEL_COUNT) ? LOG_LEVEL_NAMES[level] : "?";
    Serial.printf("[%lu] [%-5s] [%-6s] %s\n", entry.timestamp, lvlStr, tag, entry.message);

    if (_mqttForwardEnabled && _mqttPublishFn && level >= _mqttMinLevel) {
        _mqttPublishFn(entry);
    }
}

// ---- Tagged log functions ----
inline void debug(const char* tag, const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    log(LOG_DEBUG, tag, fmt, args);
    va_end(args);
}

inline void info(const char* tag, const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    log(LOG_INFO, tag, fmt, args);
    va_end(args);
}

inline void warn(const char* tag, const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    log(LOG_WARN, tag, fmt, args);
    va_end(args);
}

inline void error(const char* tag, const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    log(LOG_ERROR, tag, fmt, args);
    va_end(args);
}

inline void radar(const char* tag, const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    log(LOG_RADAR, tag, fmt, args);
    va_end(args);
}

// ---- Legacy (backward-compatible, tag defaults to SYSTEM) ----
inline void info(const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    log(LOG_INFO, TAG_SYSTEM, fmt, args);
    va_end(args);
}

inline void error(const char* fmt, ...) {
    va_list args; va_start(args, fmt);
    log(LOG_ERROR, TAG_SYSTEM, fmt, args);
    va_end(args);
}

// ---- Print radar frame ----
inline void printFrame(const RadarFrame& frame, uint32_t frameCount = 0, uint32_t errorCount = 0) {
    for (uint8_t i = 0; i < RADAR_MAX_TARGETS; i++) {
        const RadarTarget& t = frame.targets[i];
        if (t.present) {
            radar(TAG_SENSOR, "T%d X:%+d Y:%d Spd:%+dcm/s Dist:%dmm Ang:%.1f",
                  i + 1, t.x, t.y, t.speed, t.distance, t.angle);
        }
    }

    Serial.println("----------------------------------------");
    Serial.printf("[%lu] Targets: %d | Frames: %lu | Errors: %lu\n",
                  frame.timestamp, frame.targetCount, frameCount, errorCount);
    for (uint8_t i = 0; i < RADAR_MAX_TARGETS; i++) {
        const RadarTarget& t = frame.targets[i];
        if (t.present) {
            Serial.printf("  Target %d: X:%+d Y:%d Spd:%+dcm/s Dist:%dmm Ang:%.1f\n",
                          i + 1, t.x, t.y, t.speed, t.distance, t.angle);
        } else {
            Serial.printf("  Target %d: ---\n", i + 1);
        }
    }
    Serial.println("----------------------------------------");
}

} // namespace Log

#endif // LOGGER_H
