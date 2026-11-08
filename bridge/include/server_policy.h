#pragma once
#include <stdint.h>
#include <string.h>

namespace RadarServer {
constexpr const char* HOST = "systemdeveloper.ddns.net";
constexpr const char* USERNAME = "humanradar";
constexpr uint16_t PORT = 8883;
constexpr uint8_t PROTO = 3;

inline bool isPrimary(const char* host) {
    return host && strcmp(host, HOST) == 0;
}

// The bridge deliberately excludes WebSockets. Only the project's known
// endpoint can be mapped to its TCP/TLS listener without guessing a port.
inline int bridgeProtocol(const char* host, uint8_t proto, uint16_t& port) {
    if (isPrimary(host)) {
        port = PORT;
        return PROTO;
    }
    return proto == 2 || proto == 3 ? proto : -1;
}
}
