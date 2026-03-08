#ifndef RADAR_PARSER_H
#define RADAR_PARSER_H

#include "radar_types.h"

/**
 * Parse a validated 30-byte LD2450 data frame into a RadarFrame.
 *
 * Precondition: buf points to exactly RADAR_FRAME_SIZE bytes
 * with valid header and footer already verified by the caller (RadarDriver).
 *
 * This is a pure function with no side effects - easy to test independently.
 */
RadarFrame parseRadarFrame(const uint8_t* buf);

#endif // RADAR_PARSER_H
