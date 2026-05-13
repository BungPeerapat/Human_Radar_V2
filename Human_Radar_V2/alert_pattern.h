#ifndef ALERT_PATTERN_H
#define ALERT_PATTERN_H

#include <Arduino.h>

/**
 * Non-blocking state machine that drives a single GPIO (LED + buzzer in parallel)
 * to play two kinds of patterns on the Human Radar device:
 *
 *  1. WiFi-status indicator
 *     - Connecting: ON 1s / OFF 1s loop
 *     - Just connected (rising edge): 4x 250ms blink, then off
 *     - Disconnected after being connected: back to 1s/1s loop
 *
 *  2. Target-count alert (higher priority — interrupts WiFi blink)
 *     - On increase (0->N or M->N where N>M): N short beeps
 *     - On decrease (N->M where 0<M<N): 1 long beep + M short beeps
 *     - On decrease to zero (N->0):       1 long beep only
 *     - While a pattern is playing new target events are deferred and replayed
 *       only after the current pattern fully completes (no truncation).
 *     - 500 ms debounce: target count must stay stable for at least debounceMs
 *       before it becomes the new applied value.
 *
 * Hardware: single GPIO drives R+LED in parallel with a 3.3V active buzzer.
 *           Active-HIGH (logic 1 = beep+light).
 */
class AlertPattern {
public:
    struct Config {
        bool     alertEnabled  = true;   // master on/off for target beeps
        bool     ledWifiEnabled = true;  // wifi-status blink on the same pin
        uint16_t beepShortMs   = 200;
        uint16_t beepLongMs    = 800;
        uint16_t beepGapMs     = 200;
        uint16_t debounceMs    = 500;
        uint16_t maxRangeMm    = 6000;   // ignore targets farther than this (0 = no limit)
    };

    /// Bind the GPIO and configure it as OUTPUT (LOW).
    void begin(int pin);

    /// Replace runtime config. Safe to call any time.
    void setConfig(const Config& c) { config_ = c; }
    const Config& getConfig() const { return config_; }

    /// External lifecycle events
    void onWifiConnecting();      ///< slow 1s/1s blink loop
    void onWifiConnected();       ///< 4x 250ms pulse, then off
    void onWifiDisconnected();    ///< same as onWifiConnecting()

    /**
     * Feed the latest raw target count from the radar driver.
     * Debouncing + change detection is applied internally.
     *
     * The caller is responsible for applying the maxRangeMm / valid-XY filter
     * before passing the count here (see RadarFrame helper in main.cpp).
     */
    void onTargetCount(uint8_t count);

    /**
     * Manually queue a beep pattern (used by the /api/alert/test endpoint and
     * by the Android "Test ESP32 buzzer" button).
     *
     * @param shortCount  number of short beeps
     * @param longPrefix  prepend a long beep (used to imitate the "decrease" event)
     */
    void triggerTest(uint8_t shortCount, bool longPrefix);

    /**
     * Begin the "OTA in progress" indicator: 1s ON / 1s OFF heartbeat looped
     * indefinitely on GPIO26. Overrides target alerts and the WiFi blink so
     * the user knows the device is busy receiving firmware and must not be
     * powered off. Stays active until {@link #onFirmwareUpdateFinish} is
     * called or the chip reboots.
     */
    void onFirmwareUpdateStart();

    /**
     * Switch from the heartbeat to the "OTA complete, about to reboot"
     * indicator: 4 × 250 ms ON / 250 ms OFF (2 s total), then auto-stops.
     * Designed to be played just before {@code ESP.restart()} so the
     * user sees a clean handoff between "uploading" and "rebooting".
     */
    void onFirmwareUpdateFinish();

    /**
     * @return true while either the OTA heartbeat or the finish pattern is
     *         still running. Callers can busy-wait on this (with periodic
     *         {@link #update} calls) to defer a reboot until the indicator
     *         finishes.
     */
    bool isFirmwareUpdateActive() const {
        return fwUpdateMode_ != FwUpdateMode::None;
    }

    /// Must be invoked every iteration of loop().
    void update();

    /// True while a target-alert pattern is being played (alert > wifi priority).
    bool isAlertActive() const { return alertPhase_ != AlertPhase::Idle; }

private:
    enum class WifiPhase : uint8_t {
        Off,            // permanently LOW (idle, after 4x pulse finishes)
        SlowBlink,      // 1s on / 1s off loop
        FastPulse       // 4x 250ms (just connected)
    };

    enum class AlertPhase : uint8_t {
        Idle,
        LongOn,
        LongOff,        // gap after long, before short train
        ShortOn,
        ShortOff        // gap between shorts (or trailing gap before Idle)
    };

    int        pin_ = -1;
    Config     config_;

    // WiFi indicator state
    WifiPhase  wifiPhase_       = WifiPhase::Off;
    uint32_t   wifiNextChangeAt_ = 0;
    bool       wifiLevel_       = false;
    uint8_t    wifiFastRemaining_ = 0;   // counts ON edges remaining

    // Alert state
    AlertPhase alertPhase_      = AlertPhase::Idle;
    uint32_t   alertNextChangeAt_ = 0;
    uint8_t    alertShortRemaining_ = 0;
    bool       alertLevel_      = false;

    // Target count tracking
    uint8_t    appliedCount_    = 0;     // last debounced count we have observed
    uint8_t    playedCount_     = 0;     // count whose pattern was most recently played
    uint8_t    candidateCount_  = 0;
    uint32_t   candidateSince_  = 0;
    bool       candidateActive_ = false;

    // Firmware-update indicator (highest priority).
    //   None       — pin available to alert/wifi layers
    //   InProgress — 1s/1s heartbeat, indefinite
    //   Finishing  — 4× 250ms blink, auto-stops after ~2 s
    enum class FwUpdateMode : uint8_t { None, InProgress, Finishing };
    FwUpdateMode fwUpdateMode_      = FwUpdateMode::None;
    uint32_t     fwUpdateStartMs_   = 0;

    // Output
    bool       lastWrittenLevel_ = false;

    void writePin_(bool level);
    void startPatternFor_(uint8_t prev, uint8_t now);
    void advanceAlert_(uint32_t now);
    void advanceWifi_(uint32_t now);
};

extern AlertPattern alertPattern;

#endif // ALERT_PATTERN_H
