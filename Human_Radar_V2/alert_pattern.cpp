#include "alert_pattern.h"
#include "logger.h"

AlertPattern alertPattern;

namespace {
constexpr uint16_t kWifiSlowOnMs   = 1000;
constexpr uint16_t kWifiSlowOffMs  = 1000;
constexpr uint16_t kWifiFastOnMs   = 250;
constexpr uint16_t kWifiFastOffMs  = 250;
constexpr uint8_t  kWifiFastPulses = 4;
constexpr char     TAG[]           = "ALERT";
}

void AlertPattern::begin(int pin) {
    pin_ = pin;
    pinMode(pin_, OUTPUT);
    writePin_(false);
    Log::info(TAG, "AlertPattern bound to GPIO%d", pin_);
}

void AlertPattern::writePin_(bool level) {
    if (pin_ < 0) return;
    if (level != lastWrittenLevel_) {
        digitalWrite(pin_, level ? HIGH : LOW);
        lastWrittenLevel_ = level;
    }
}

// ---------------------------------------------------------------------------
//  WiFi events
// ---------------------------------------------------------------------------
void AlertPattern::onWifiConnecting() {
    if (wifiPhase_ == WifiPhase::SlowBlink) return;
    wifiPhase_         = WifiPhase::SlowBlink;
    wifiLevel_         = true;
    wifiNextChangeAt_  = millis() + kWifiSlowOnMs;
    Log::info(TAG, "WiFi connecting -> slow blink");
}

void AlertPattern::onWifiConnected() {
    wifiPhase_         = WifiPhase::FastPulse;
    wifiFastRemaining_ = kWifiFastPulses;
    wifiLevel_         = true;
    wifiNextChangeAt_  = millis() + kWifiFastOnMs;
    Log::info(TAG, "WiFi connected -> 4x pulse");
}

void AlertPattern::onWifiDisconnected() {
    onWifiConnecting();
}

// ---------------------------------------------------------------------------
//  Target count
// ---------------------------------------------------------------------------
void AlertPattern::onTargetCount(uint8_t count) {
    if (!config_.alertEnabled) {
        appliedCount_   = count;
        playedCount_    = count;
        candidateActive_ = false;
        return;
    }

    const uint32_t now = millis();

    // Start / continue debounce window
    if (count != appliedCount_) {
        if (!candidateActive_ || count != candidateCount_) {
            candidateCount_  = count;
            candidateSince_  = now;
            candidateActive_ = true;
            return;
        }
        if (now - candidateSince_ >= config_.debounceMs) {
            const uint8_t prev = appliedCount_;
            appliedCount_      = candidateCount_;
            candidateActive_   = false;
            // Defer pattern start if one is currently playing.
            if (alertPhase_ == AlertPhase::Idle) {
                startPatternFor_(prev, appliedCount_);
                playedCount_ = appliedCount_;
            }
        }
    } else {
        candidateActive_ = false;
    }
}

void AlertPattern::startPatternFor_(uint8_t prev, uint8_t now) {
    if (!config_.alertEnabled) return;
    if (prev == now) return;

    bool    longPrefix = false;
    uint8_t shorts     = 0;

    if (now > prev) {
        // Increase event -> N short beeps where N == new count
        shorts = now;
    } else if (now == 0) {
        // Drop to zero -> single long beep, no shorts
        longPrefix = true;
        shorts     = 0;
    } else {
        // Decrease but >0 -> long beep + M short beeps
        longPrefix = true;
        shorts     = now;
    }

    Log::info(TAG, "Pattern start prev=%u now=%u (long=%d, shorts=%u)",
              prev, now, (int)longPrefix, shorts);

    if (longPrefix) {
        alertPhase_         = AlertPhase::LongOn;
        alertLevel_         = true;
        alertNextChangeAt_  = millis() + config_.beepLongMs;
        alertShortRemaining_ = shorts;
    } else if (shorts > 0) {
        alertPhase_         = AlertPhase::ShortOn;
        alertLevel_         = true;
        alertNextChangeAt_  = millis() + config_.beepShortMs;
        alertShortRemaining_ = shorts - 1;
    } else {
        alertPhase_ = AlertPhase::Idle;
    }
}

void AlertPattern::onFirmwareUpdateStart() {
    fwUpdateMode_    = FwUpdateMode::InProgress;
    fwUpdateStartMs_ = millis();
    Log::info(TAG, "OTA heartbeat started (1s/1s, indefinite)");
}

void AlertPattern::onFirmwareUpdateFinish() {
    fwUpdateMode_    = FwUpdateMode::Finishing;
    fwUpdateStartMs_ = millis();
    Log::info(TAG, "OTA finishing pattern: 4x 250ms blink");
}

namespace {
constexpr uint32_t OTA_HEARTBEAT_PERIOD = 2000;  // 1s on / 1s off
constexpr uint32_t OTA_HEARTBEAT_ON     = 1000;
constexpr uint32_t OTA_FINISH_CYCLES    = 4;
constexpr uint32_t OTA_FINISH_PERIOD    = 500;   // 0.25s on + 0.25s off
constexpr uint32_t OTA_FINISH_ON        = 250;
constexpr uint32_t OTA_FINISH_TOTAL     = OTA_FINISH_CYCLES * OTA_FINISH_PERIOD; // 2000 ms

// Heartbeat is unbounded — caller decides when to call onFirmwareUpdateFinish().
bool computeOtaHeartbeatLevel(uint32_t elapsedMs) {
    return (elapsedMs % OTA_HEARTBEAT_PERIOD) < OTA_HEARTBEAT_ON;
}

// 4× 0.25s blinks then done. Returns level + sets *doneOut once the four
// pulses have played out.
bool computeOtaFinishLevel(uint32_t elapsedMs, bool* doneOut) {
    if (elapsedMs >= OTA_FINISH_TOTAL) {
        if (doneOut) *doneOut = true;
        return false;
    }
    if (doneOut) *doneOut = false;
    return (elapsedMs % OTA_FINISH_PERIOD) < OTA_FINISH_ON;
}
}

void AlertPattern::triggerTest(uint8_t shortCount, bool longPrefix) {
    if (alertPhase_ != AlertPhase::Idle) {
        Log::warn(TAG, "Test trigger ignored: pattern busy");
        return;
    }
    Log::info(TAG, "Test trigger: long=%d shorts=%u", (int)longPrefix, shortCount);
    if (longPrefix) {
        alertPhase_         = AlertPhase::LongOn;
        alertLevel_         = true;
        alertNextChangeAt_  = millis() + config_.beepLongMs;
        alertShortRemaining_ = shortCount;
    } else if (shortCount > 0) {
        alertPhase_         = AlertPhase::ShortOn;
        alertLevel_         = true;
        alertNextChangeAt_  = millis() + config_.beepShortMs;
        alertShortRemaining_ = shortCount - 1;
    }
}

// ---------------------------------------------------------------------------
//  Loop driver
// ---------------------------------------------------------------------------
void AlertPattern::update() {
    const uint32_t now = millis();
    advanceAlert_(now);
    advanceWifi_(now);

    // Resolve final pin level: firmware-update indicator > alert > wifi > off
    bool out = false;
    if (fwUpdateMode_ == FwUpdateMode::InProgress) {
        // Indefinite heartbeat — never auto-completes; finish only when the
        // caller explicitly switches us to Finishing or None.
        out = computeOtaHeartbeatLevel(now - fwUpdateStartMs_);
    } else if (fwUpdateMode_ == FwUpdateMode::Finishing) {
        bool done = false;
        out = computeOtaFinishLevel(now - fwUpdateStartMs_, &done);
        if (done) {
            fwUpdateMode_ = FwUpdateMode::None;
            Log::info(TAG, "OTA finishing pattern completed");
        }
    } else if (alertPhase_ != AlertPhase::Idle) {
        out = alertLevel_;
    } else if (config_.ledWifiEnabled && wifiPhase_ != WifiPhase::Off) {
        out = wifiLevel_;
    }
    writePin_(out);

    // If an alert just finished and applied count drifted while we were busy,
    // emit a follow-up pattern.
    if (alertPhase_ == AlertPhase::Idle && playedCount_ != appliedCount_) {
        const uint8_t prev = playedCount_;
        playedCount_       = appliedCount_;
        startPatternFor_(prev, appliedCount_);
    }
}

void AlertPattern::advanceAlert_(uint32_t now) {
    if (alertPhase_ == AlertPhase::Idle) return;
    if ((int32_t)(now - alertNextChangeAt_) < 0) return;

    switch (alertPhase_) {
        case AlertPhase::LongOn:
            alertLevel_        = false;
            alertPhase_        = AlertPhase::LongOff;
            alertNextChangeAt_ = now + config_.beepGapMs;
            break;
        case AlertPhase::LongOff:
            if (alertShortRemaining_ == 0) {
                alertPhase_ = AlertPhase::Idle;
            } else {
                alertLevel_        = true;
                alertPhase_        = AlertPhase::ShortOn;
                alertNextChangeAt_ = now + config_.beepShortMs;
                alertShortRemaining_--;
            }
            break;
        case AlertPhase::ShortOn:
            alertLevel_        = false;
            alertPhase_        = AlertPhase::ShortOff;
            alertNextChangeAt_ = now + config_.beepGapMs;
            break;
        case AlertPhase::ShortOff:
            if (alertShortRemaining_ == 0) {
                alertPhase_ = AlertPhase::Idle;
            } else {
                alertLevel_        = true;
                alertPhase_        = AlertPhase::ShortOn;
                alertNextChangeAt_ = now + config_.beepShortMs;
                alertShortRemaining_--;
            }
            break;
        default:
            break;
    }
}

void AlertPattern::advanceWifi_(uint32_t now) {
    if (wifiPhase_ == WifiPhase::Off) {
        wifiLevel_ = false;
        return;
    }
    if ((int32_t)(now - wifiNextChangeAt_) < 0) return;

    if (wifiPhase_ == WifiPhase::SlowBlink) {
        wifiLevel_         = !wifiLevel_;
        wifiNextChangeAt_  = now + (wifiLevel_ ? kWifiSlowOnMs : kWifiSlowOffMs);
        return;
    }

    // FastPulse: alternate ON/OFF; count down on each OFF edge
    if (wifiLevel_) {
        wifiLevel_         = false;
        wifiNextChangeAt_  = now + kWifiFastOffMs;
        if (--wifiFastRemaining_ == 0) {
            wifiPhase_ = WifiPhase::Off;  // done; next OFF transition shuts indicator
        }
    } else {
        wifiLevel_         = true;
        wifiNextChangeAt_  = now + kWifiFastOnMs;
    }
}
