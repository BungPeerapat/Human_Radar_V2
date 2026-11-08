#include "ota_download.h"
#include <cassert>
#include <iostream>

Fake::State Fake::state;
UpdateClass Update;

static void reset() {
    Fake::state = Fake::State{};
    Fake::state.image.assign(4096, 0x42);
    Fake::state.image[0] = 0xe9;
    Fake::state.contentLength = 4096;
}
static std::string digest() {
    uint8_t hash[32];
    SHA256(Fake::state.image.data(), Fake::state.image.size(), hash);
    const char* hex = "0123456789abcdef";
    std::string result;
    for (uint8_t byte : hash) { result += hex[byte >> 4]; result += hex[byte & 15]; }
    return result;
}
static const char* pull(const std::string& sha, size_t size = 4096) {
    return downloadVerifiedOta("https://example.com/fw.bin", sha.c_str(), size,
                              []() {}, [](size_t, size_t) {});
}
int main() {
    reset(); assert(pull(digest()) == nullptr); assert(Fake::state.committed); assert(Fake::state.trusted);
    assert(Fake::state.headerWasGuarded); assert(Fake::state.watchdog);
    assert(Fake::state.tlsTimeout == 10 && Fake::state.handshakeTimeout == 5);
    assert(Fake::state.connectTimeout == 5000 && Fake::state.headerTimeout == 15000);
    assert(Fake::state.redirectLimit == 3);
    reset(); assert(pull(std::string(64, '0'))); assert(Fake::state.aborted); assert(!Fake::state.committed);
    reset(); std::string sha = digest(); Fake::state.image.resize(2048); Fake::state.disconnected = true;
    assert(pull(sha)); assert(Fake::state.aborted); assert(!Fake::state.committed);
    reset(); Fake::state.stall = true; assert(pull(digest())); assert(Fake::state.aborted);
    reset(); Fake::state.contentLength = -1; assert(pull(digest())); assert(!Fake::state.beginCalled);
    reset(); Fake::state.contentLength = 4095; assert(pull(digest())); assert(!Fake::state.beginCalled);
    reset(); Fake::state.capacity = 2048; assert(pull(digest())); assert(!Fake::state.beginCalled);
    reset(); Fake::state.httpCode = 404; assert(pull(digest())); assert(!Fake::state.beginCalled);
    assert(Fake::state.headerWasGuarded); assert(Fake::state.watchdog);
    reset(); Fake::state.image[0] = 0; assert(pull(digest())); assert(Fake::state.aborted);
    reset(); Fake::state.beginFails = true; assert(pull(digest())); assert(!Fake::state.committed);
    reset(); Fake::state.writeFails = true; assert(pull(digest())); assert(Fake::state.aborted);
    reset(); Fake::state.endFails = true; assert(pull(digest())); assert(Fake::state.aborted);
    std::cout << "12 verified OTA transfer scenarios passed\n";
}
