#include "ota_policy.h"
#include "server_policy.h"
#include <cassert>
#include <cstring>
#include <iostream>

int main() {
    const char* sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    assert(OtaPolicy::validRequest("https://example.com/fw.bin", sha, 1000000, 1310720));
    assert(!OtaPolicy::validRequest("http://example.com/fw.bin", sha, 1000000, 1310720));
    assert(!OtaPolicy::validRequest("https://example.com/fw.bin", "", 1000000, 1310720));
    assert(!OtaPolicy::validRequest("https://example.com/fw.bin", sha, 0, 1310720));
    assert(!OtaPolicy::validRequest("https://example.com/fw.bin", sha, 1310721, 1310720));
    assert(!OtaPolicy::validRequest("https://user:pw@example.com/fw.bin", sha, 10, 100));
    assert(!OtaPolicy::validRequest("https:///fw.bin", sha, 10, 100));
    assert(!OtaPolicy::complete(999, 1000));
    assert(!OtaPolicy::complete(0, 0));
    assert(OtaPolicy::complete(1000, 1000));
    assert(!OtaPolicy::complete(1001, 1000));
    assert(OtaPolicy::timedOut(60001, 0));
    assert(!OtaPolicy::timedOut(59999, 0));
    assert(OtaPolicy::timedOut(60000, 0xfffffff0u)); // millis wrap-around
    assert(std::strcmp(RadarServer::HOST, "systemdeveloper.ddns.net") == 0);
    assert(RadarServer::PORT == 8883);
    assert(RadarServer::PROTO == 3);
    unsigned short port = 9002;
    assert(RadarServer::bridgeProtocol(RadarServer::HOST, 1, port) == 3 && port == 8883);
    port = 1883;
    assert(RadarServer::bridgeProtocol("custom.local", 2, port) == 2 && port == 1883);
    port = 443;
    assert(RadarServer::bridgeProtocol("custom.local", 1, port) == -1 && port == 443);
    std::cout << "OTA and broker policy tests passed\n";
}
