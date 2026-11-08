#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <openssl/sha.h>
#define PROGMEM

namespace Fake {
struct State {
    std::vector<uint8_t> image;
    size_t position = 0;
    int httpCode = 200;
    int contentLength = 0;
    size_t capacity = 1310720;
    uint32_t clock = 0;
    bool stall = false;
    bool disconnected = false;
    bool beginFails = false;
    bool writeFails = false;
    bool endFails = false;
    bool beginCalled = false;
    bool aborted = false;
    bool committed = false;
    bool trusted = false;
    bool watchdog = true;
    bool headerWasGuarded = false;
    unsigned tlsTimeout = 0;
    unsigned handshakeTimeout = 0;
    int connectTimeout = 0;
    int headerTimeout = 0;
    int redirectLimit = 0;
};
extern State state;
}
inline uint32_t millis() { return ++Fake::state.clock; }
inline void delay(unsigned value) { Fake::state.clock += value; }
inline void yield() {}
inline void configTime(long, int, const char*, const char*) {}

class WiFiClient {
public:
    size_t available() {
        return Fake::state.stall ? 0 : Fake::state.image.size() - Fake::state.position;
    }
    int read(uint8_t* destination, size_t count) {
        count = std::min(count, available());
        std::memcpy(destination, Fake::state.image.data() + Fake::state.position, count);
        Fake::state.position += count;
        return static_cast<int>(count);
    }
};
class WiFiClientSecure : public WiFiClient {
public:
    void setCACert(const char* pem) { Fake::state.trusted = pem && std::strstr(pem, "BEGIN CERTIFICATE"); }
    void setTimeout(unsigned value) { Fake::state.tlsTimeout = value; }
    void setHandshakeTimeout(unsigned value) { Fake::state.handshakeTimeout = value; }
};
constexpr int HTTPC_STRICT_FOLLOW_REDIRECTS = 1;
constexpr int HTTP_CODE_OK = 200;
class HTTPClient {
    WiFiClient client;
public:
    void setFollowRedirects(int) {}
    void setReuse(bool) {}
    void setTimeout(int value) { Fake::state.headerTimeout = value; }
    void setConnectTimeout(int value) { Fake::state.connectTimeout = value; }
    void setRedirectLimit(int value) { Fake::state.redirectLimit = value; }
    bool begin(WiFiClientSecure&, const char*) { return true; }
    int GET() { Fake::state.headerWasGuarded = !Fake::state.watchdog; return Fake::state.httpCode; }
    int getSize() { return Fake::state.contentLength; }
    WiFiClient* getStreamPtr() { return &client; }
    bool connected() { return !Fake::state.disconnected; }
    void end() {}
};
class UpdateClass {
public:
    bool isRunning() { return false; }
    bool begin(size_t) { Fake::state.beginCalled = true; return !Fake::state.beginFails; }
    size_t write(uint8_t*, size_t count) { return Fake::state.writeFails ? 0 : count; }
    void abort() { Fake::state.aborted = true; }
    bool end() { Fake::state.committed = !Fake::state.endFails; return Fake::state.committed; }
};
extern UpdateClass Update;
struct esp_partition_t { size_t size; };
inline const esp_partition_t* esp_ota_get_next_update_partition(void*) {
    static esp_partition_t partition;
    partition.size = Fake::state.capacity;
    return &partition;
}
constexpr int ESP_OK = 0;
inline int esp_task_wdt_status(void*) { return Fake::state.watchdog ? ESP_OK : -1; }
inline int esp_task_wdt_delete(void*) { Fake::state.watchdog = false; return ESP_OK; }
inline int esp_task_wdt_add(void*) { Fake::state.watchdog = true; return ESP_OK; }
using mbedtls_sha256_context = SHA256_CTX;
inline void mbedtls_sha256_init(mbedtls_sha256_context*) {}
inline int mbedtls_sha256_starts_ret(mbedtls_sha256_context* context, int) { return SHA256_Init(context) == 1 ? 0 : -1; }
inline int mbedtls_sha256_update_ret(mbedtls_sha256_context* context, const uint8_t* data, size_t count) { return SHA256_Update(context, data, count) == 1 ? 0 : -1; }
inline int mbedtls_sha256_finish_ret(mbedtls_sha256_context* context, uint8_t* digest) { return SHA256_Final(digest, context) == 1 ? 0 : -1; }
inline void mbedtls_sha256_free(mbedtls_sha256_context*) {}
