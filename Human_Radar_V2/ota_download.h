#pragma once
#include "ota_policy.h"
#include "mqtt_trust.h"
#include "ota_trust.h"
#include <HTTPClient.h>
#include <ctype.h>
#include <Update.h>
#include <WiFiClientSecure.h>
#include <esp_ota_ops.h>
#include <esp_task_wdt.h>
#include <mbedtls/sha256.h>

// HTTPClient cannot feed the caller's task watchdog during a blocking TLS
// handshake/header read. Suspend only this task while performing the bounded
// request, then restore its subscription on every exit path.
class OtaRequestWatchdogPause {
    bool restore = false;
public:
    OtaRequestWatchdogPause() {
        if (esp_task_wdt_status(nullptr) == ESP_OK) restore = esp_task_wdt_delete(nullptr) == ESP_OK;
    }
    ~OtaRequestWatchdogPause() { if (restore) esp_task_wdt_add(nullptr); }
};

// Both normal and rescue firmware use the same commit gate. No image becomes
// bootable until exact length AND the authenticated command's SHA-256 match.
// tick must not call mqtt.loop(): this function runs inside its callback.
template <typename Tick, typename Progress>
const char* downloadVerifiedOta(const char* url, const char* sha, size_t expected,
                              Tick tick, Progress progress) {
    const esp_partition_t* partition = esp_ota_get_next_update_partition(nullptr);
    if (!partition || !OtaPolicy::validRequest(url, sha, expected, partition->size))
        return "OTA requires HTTPS, SHA-256 and a size that fits the OTA slot";
    if (!mqttClockReady()) return "Waiting for SNTP clock; retry OTA shortly";
    if (Update.isRunning()) return "Another firmware update is active";

    WiFiClientSecure tls;
    tls.setCACert(OTA_ROOT_CA);
    tls.setTimeout(10); // WiFiClientSecure uses seconds
    tls.setHandshakeTimeout(5);
    HTTPClient http;
    http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
    http.setReuse(false);
    http.setTimeout(15000);
    http.setConnectTimeout(5000);
    http.setRedirectLimit(3);
    if (!http.begin(tls, url)) return "HTTPS initialization failed";
    tick();
    int code;
    {
        OtaRequestWatchdogPause pause;
        code = http.GET();
    }
    if (code != HTTP_CODE_OK) { http.end(); return "HTTPS GET failed"; }
    // Raw HTTPClient streams expose chunk framing; require Content-Length.
    if (http.getSize() <= 0 || static_cast<size_t>(http.getSize()) != expected) {
        http.end(); return "Content-Length does not match firmware manifest";
    }
    tick();
    if (!Update.begin(expected)) { http.end(); return "OTA slot initialization failed"; }

    mbedtls_sha256_context hash;
    mbedtls_sha256_init(&hash);
    int hashError = mbedtls_sha256_starts_ret(&hash, 0);
    WiFiClient* stream = http.getStreamPtr();
    uint8_t buffer[1024];
    size_t written = 0;
    const uint32_t started = millis();
    uint32_t lastData = started;
    uint32_t lastReport = started;
    const char* error = nullptr;
    while (!hashError && written < expected) {
        tick();
        const uint32_t now = millis();
        if (OtaPolicy::timedOut(now, lastData) || now - started >= 180000u) {
            error = "OTA download timeout"; break;
        }
        size_t available = stream->available();
        if (!available) {
            if (!http.connected()) { error = "Incomplete firmware download"; break; }
            delay(1); continue;
        }
        size_t count = available > sizeof(buffer) ? sizeof(buffer) : available;
        if (count > expected - written) count = expected - written;
        const int got = stream->read(buffer, count);
        if (got <= 0) { delay(1); continue; }
        if (written == 0 && buffer[0] != 0xe9) { error = "Not an ESP32 firmware image"; break; }
        hashError = mbedtls_sha256_update_ret(&hash, buffer, static_cast<size_t>(got));
        if (hashError) break;
        if (Update.write(buffer, got) != static_cast<size_t>(got)) {
            error = "OTA flash write failed"; break;
        }
        written += got;
        lastData = millis();
        if (lastData - lastReport >= 2000u) {
            progress(written, expected);
            lastReport = lastData;
        }
        yield();
    }
    http.end();
    uint8_t digest[32];
    if (!hashError) hashError = mbedtls_sha256_finish_ret(&hash, digest);
    mbedtls_sha256_free(&hash);
    if (!error && hashError) error = "SHA-256 calculation failed";
    if (!error && !OtaPolicy::complete(written, expected)) error = "Incomplete firmware download";
    if (!error) {
        const char* hex = "0123456789abcdef";
        for (size_t i = 0; i < 32; ++i) {
            const char high = static_cast<char>(tolower(static_cast<unsigned char>(sha[i * 2])));
            const char low = static_cast<char>(tolower(static_cast<unsigned char>(sha[i * 2 + 1])));
            if (high != hex[digest[i] >> 4] || low != hex[digest[i] & 15]) {
                error = "SHA-256 mismatch; firmware rejected"; break;
            }
        }
    }
    if (error) { Update.abort(); return error; }
    tick();
    if (!Update.end()) { Update.abort(); return "Firmware validation failed"; }
    return nullptr;
}
