#include "radar_parser.h"

/**
 * Parse a single target from an 8-byte block.
 *
 * Byte layout (little-endian):
 *   [0-1] X coordinate  (custom signed, mm)
 *   [2-3] Y coordinate  (custom signed, mm)
 *   [4-5] Speed         (custom signed, cm/s)
 *   [6-7] Distance res  (unsigned, mm)
 *
 * Sign encoding (confirmed by HLK reference code):
 *   bit15 = 1 -> positive (subtract 0x8000 for magnitude)
 *   bit15 = 0 -> negative (raw value IS the magnitude)
 *   Y is always positive (always has bit15=1)
 */
static RadarTarget parseTarget(const uint8_t* data) {
    RadarTarget t;

    uint16_t rawX    = readU16LE(&data[0]);
    uint16_t rawY    = readU16LE(&data[2]);
    uint16_t rawSpd  = readU16LE(&data[4]);
    uint16_t rawDist = readU16LE(&data[6]);

    // Decode custom sign encoding
    t.x       = decodeSignedValue(rawX);
    t.y       = decodeSignedValue(rawY);
    t.speed   = decodeSignedValue(rawSpd);
    t.distRes = rawDist;

    // Target is absent if all raw values are zero
    t.present = (rawX != 0 || rawY != 0 || rawSpd != 0 || rawDist != 0);

    // Compute derived fields
    if (t.present) {
        // Euclidean distance from sensor to target
        float fx = (float)t.x;
        float fy = (float)t.y;
        t.distance = (uint16_t)sqrtf(fx * fx + fy * fy);

        // Angle: 0 = straight ahead, negative = left, positive = right
        // atan2(x, y) because Y is the forward axis
        t.angle = atan2f(fx, fy) * 180.0f / M_PI;
    } else {
        t.distance = 0;
        t.angle    = 0.0f;
    }

    return t;
}

RadarFrame parseRadarFrame(const uint8_t* buf) {
    RadarFrame frame;
    frame.targetCount = 0;
    frame.timestamp   = millis();

    for (uint8_t i = 0; i < RADAR_MAX_TARGETS; i++) {
        const uint8_t* targetData = &buf[RADAR_TARGET_DATA_OFFSET + (i * RADAR_TARGET_SIZE)];
        frame.targets[i] = parseTarget(targetData);

        if (frame.targets[i].present) {
            frame.targetCount++;
        }
    }

    return frame;
}
