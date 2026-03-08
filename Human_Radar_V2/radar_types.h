#ifndef RADAR_TYPES_H
#define RADAR_TYPES_H

#include <Arduino.h>
#include <math.h>

// ============================================================================
// HLK-LD2450 Protocol Constants
// ============================================================================

static constexpr uint32_t RADAR_BAUD           = 256000;
static constexpr uint16_t RADAR_RX_BUF_SIZE    = 512;   // UART RX buffer size
static constexpr uint32_t RADAR_FRAME_TIMEOUT  = 200;   // ms, reset if frame incomplete

// Data frame: [Header 4B] [Target1 8B] [Target2 8B] [Target3 8B] [Footer 2B]
static constexpr uint8_t  RADAR_FRAME_SIZE        = 30;
static constexpr uint8_t  RADAR_HEADER_SIZE       = 4;
static constexpr uint8_t  RADAR_FOOTER_SIZE       = 2;
static constexpr uint8_t  RADAR_TARGET_SIZE       = 8;
static constexpr uint8_t  RADAR_MAX_TARGETS       = 3;
static constexpr uint8_t  RADAR_TARGET_DATA_OFFSET = RADAR_HEADER_SIZE;

// Frame header: AA FF 03 00
static constexpr uint8_t RADAR_HEADER[RADAR_HEADER_SIZE] = {0xAA, 0xFF, 0x03, 0x00};

// Frame footer: 55 CC
static constexpr uint8_t RADAR_FOOTER[RADAR_FOOTER_SIZE] = {0x55, 0xCC};

// ============================================================================
// Data Structures
// ============================================================================

struct RadarTarget {
    // Raw fields from sensor
    int16_t  x;           // mm, positive = right of sensor, negative = left
    int16_t  y;           // mm, distance forward (always positive in normal operation)
    int16_t  speed;       // cm/s, negative = approaching, positive = receding
    uint16_t distRes;     // mm, distance resolution (range gate size)

    // Computed fields
    uint16_t distance;    // mm, euclidean distance = sqrt(x^2 + y^2)
    float    angle;       // degrees, 0 = straight ahead, negative = left, positive = right

    bool     present;     // false if target slot is empty (all zeros)
};

struct RadarFrame {
    RadarTarget targets[RADAR_MAX_TARGETS];
    uint8_t     targetCount;  // number of present targets (0-3)
    uint32_t    timestamp;    // millis() when frame was received
};

// ============================================================================
// Helpers
// ============================================================================

/**
 * Decode the LD2450 custom sign encoding.
 *
 * The LD2450 does NOT use two's complement. Instead:
 *   - Bit 15 = 1 -> positive value
 *   - Bit 15 = 0 -> negative value
 *   - Bits 14:0  -> absolute magnitude
 *
 * Confirmed by HLK official reference code:
 *   if (high_byte & 0x80) value -= 0x8000;  // positive
 *   else value is negative magnitude
 */
inline int16_t decodeSignedValue(uint16_t raw) {
    int16_t magnitude = raw & 0x7FFF;
    return (raw & 0x8000) ? magnitude : -magnitude;
}

/**
 * Read a 16-bit unsigned value in little-endian byte order.
 */
inline uint16_t readU16LE(const uint8_t* p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

#endif // RADAR_TYPES_H
