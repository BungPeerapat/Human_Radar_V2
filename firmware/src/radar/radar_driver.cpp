#include "radar_driver.h"
#include "radar_parser.h"

void RadarDriver::begin(int rxPin, int txPin, int uartNum) {
    _serial = new HardwareSerial(uartNum);

    // ESP32-WROOM32: Increase RX buffer BEFORE begin() to prevent data loss
    // at 256000 baud. Default 256 bytes is tight when Serial.printf blocks.
    _serial->setRxBufferSize(RADAR_RX_BUF_SIZE);

    _serial->begin(RADAR_BAUD, SERIAL_8N1, rxPin, txPin);
    resetState();
}

bool RadarDriver::update() {
    if (!_serial) return false;

    bool gotFrame = false;

    // Frame timeout: if we started reading a frame but it never completes,
    // reset state. This prevents the state machine from getting stuck when
    // bytes are lost mid-frame (e.g., loose wiring, noise).
    if (_state == State::READ_BODY &&
        (millis() - _lastByteTime) > RADAR_FRAME_TIMEOUT) {
        _errorCount++;
        resetState();
    }

    // Drain all available bytes from UART buffer
    while (_serial->available() > 0) {
        uint8_t byte = _serial->read();
        _lastByteTime = millis();

        switch (_state) {

        case State::FIND_HEADER:
            // Match bytes sequentially against the frame header: AA FF 03 00
            if (byte == RADAR_HEADER[_headerMatchCount]) {
                _buf[_headerMatchCount] = byte;
                _headerMatchCount++;

                if (_headerMatchCount == RADAR_HEADER_SIZE) {
                    // Full header matched - start reading body
                    _bufIndex = RADAR_HEADER_SIZE;
                    _state = State::READ_BODY;
                }
            } else {
                // Mismatch - check if this byte could be the start of a new header
                // This handles cases like: AA AA FF 03 00 (overlapping pattern)
                if (byte == RADAR_HEADER[0]) {
                    _buf[0] = byte;
                    _headerMatchCount = 1;
                } else {
                    _headerMatchCount = 0;
                }
            }
            break;

        case State::READ_BODY:
            _buf[_bufIndex++] = byte;

            if (_bufIndex == RADAR_FRAME_SIZE) {
                // Buffer full - validate footer bytes at positions 28 and 29
                if (_buf[RADAR_FRAME_SIZE - 2] == RADAR_FOOTER[0] &&
                    _buf[RADAR_FRAME_SIZE - 1] == RADAR_FOOTER[1]) {
                    // Valid frame - parse it
                    _frame = parseRadarFrame(_buf);
                    _frameCount++;
                    gotFrame = true;
                } else {
                    // Invalid footer - corrupted frame
                    _errorCount++;
                }
                resetState();
            }
            break;
        }
    }

    return gotFrame;
}

void RadarDriver::resetState() {
    _state = State::FIND_HEADER;
    _headerMatchCount = 0;
    _bufIndex = 0;
}
