#pragma once
#include <stdint.h>
#include <string.h>
#include <ctype.h>

namespace RadarServer {
constexpr const char* HOST = "systemdeveloper.ddns.net";
constexpr const char* USERNAME = "humanradar";
constexpr uint16_t PORT = 8883;
constexpr uint8_t PROTO = 3;

inline bool isPrimary(const char* host) {
    if (!host) return false;
    size_t length = strlen(host);
    if (length && host[length - 1] == '.') --length;
    if (length != strlen(HOST)) return false;
    for (size_t i = 0; i < length; ++i) {
        if (tolower(static_cast<unsigned char>(host[i])) != HOST[i]) return false;
    }
    return true;
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
