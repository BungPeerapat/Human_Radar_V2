#ifndef RADAR_DRIVER_H
#define RADAR_DRIVER_H

#include <HardwareSerial.h>
#include "radar_types.h"

/**
 * RadarDriver: Handles UART communication and frame synchronization
 * for the HLK-LD2450 mmWave radar sensor.
 *
 * Uses a simple state machine to find frame headers, read frame bodies,
 * and validate footers. Delegates actual byte decoding to radar_parser.
 *
 * Usage:
 *   RadarDriver radar;
 *   radar.begin(16, 17);          // RX=GPIO16, TX=GPIO17
 *   if (radar.update()) {         // call in loop()
 *       auto& frame = radar.getLatestFrame();
 *       // use frame.targets[0..2]
 *   }
 */
class RadarDriver {
public:
    /**
     * Initialize UART for radar communication.
     * @param rxPin  ESP32 GPIO for receiving radar data (connect to LD2450 TX)
     * @param txPin  ESP32 GPIO for sending commands (connect to LD2450 RX)
     * @param uartNum  Hardware UART number (default 2; avoid 0 which is USB debug)
     */
    void begin(int rxPin, int txPin, int uartNum = 2);

    /**
     * Process available UART data. Call this every loop() iteration.
     * @return true if a new complete frame was parsed this call
     */
    bool update();

    /** Get the most recently parsed frame. Only meaningful after update() returns true. */
    const RadarFrame& getLatestFrame() const { return _frame; }

    /** Total number of successfully parsed frames since begin(). */
    uint32_t getFrameCount() const { return _frameCount; }

    /** Total number of frames that failed footer validation since begin(). */
    uint32_t getErrorCount() const { return _errorCount; }

private:
    enum class State : uint8_t {
        FIND_HEADER,  // Scanning for AA FF 03 00
        READ_BODY     // Reading remaining bytes until buffer full
    };

    HardwareSerial* _serial           = nullptr;
    RadarFrame      _frame            = {};
    uint8_t         _buf[RADAR_FRAME_SIZE] = {};
    uint8_t         _bufIndex         = 0;
    uint8_t         _headerMatchCount = 0;
    State           _state            = State::FIND_HEADER;
    uint32_t        _frameCount       = 0;
    uint32_t        _errorCount       = 0;
    uint32_t        _lastByteTime     = 0;  // for frame timeout detection

    void resetState();
};

#endif // RADAR_DRIVER_H
