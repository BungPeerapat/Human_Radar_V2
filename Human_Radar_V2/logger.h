#ifndef LOGGER_H
#define LOGGER_H

#include <Arduino.h>
#include "radar_types.h"

/**
 * Minimal logging utilities for serial debug output.
 *
 * All output goes to Serial (USB). In future phases, these can be
 * redirected to MQTT, SD card, or other outputs without changing
 * application code.
 */
namespace Log {

inline void info(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    Serial.printf("[%lu] ", millis());
    char buf[256];
    vsnprintf(buf, sizeof(buf), fmt, args);
    Serial.println(buf);
    va_end(args);
}

inline void error(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    Serial.printf("[%lu] ERROR: ", millis());
    char buf[256];
    vsnprintf(buf, sizeof(buf), fmt, args);
    Serial.println(buf);
    va_end(args);
}

/**
 * Print a full radar frame in a readable format.
 *
 * Example output:
 *   [12340] Targets: 2 | Frames: 150 | Errors: 0
 *     Target 1:
 *       X: -782 mm
 *       Y: 1713 mm
 *       Speed: -16 cm/s
 *       Distance: 1883 mm
 *       Angle: -24.5 deg
 *     Target 2:
 *       X: 450 mm
 *       Y: 2200 mm
 *       Speed: 8 cm/s
 *       Distance: 2246 mm
 *       Angle: 11.6 deg
 *     Target 3: ---
 */
inline void printFrame(const RadarFrame& frame, uint32_t frameCount = 0, uint32_t errorCount = 0) {
    Serial.println("----------------------------------------");
    Serial.printf("[%lu] Targets: %d | Frames: %lu | Errors: %lu\n",
                  frame.timestamp, frame.targetCount, frameCount, errorCount);

    for (uint8_t i = 0; i < RADAR_MAX_TARGETS; i++) {
        const RadarTarget& t = frame.targets[i];
        if (t.present) {
            Serial.printf("  Target %d:\n", i + 1);
            Serial.printf("    X:        %+d mm\n", t.x);
            Serial.printf("    Y:        %d mm\n", t.y);
            Serial.printf("    Speed:    %+d cm/s\n", t.speed);
            Serial.printf("    Distance: %d mm\n", t.distance);
            Serial.printf("    Angle:    %.1f deg\n", t.angle);
        } else {
            Serial.printf("  Target %d: ---\n", i + 1);
        }
    }
    Serial.println("----------------------------------------");
    Serial.println();
}

} // namespace Log

#endif // LOGGER_H
