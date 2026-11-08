#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace OtaPolicy {
inline bool validSha256(const char* sha) {
    if (!sha || strlen(sha) != 64) return false;
    for (size_t i = 0; i < 64; ++i) {
        const char c = sha[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
              (c >= 'A' && c <= 'F'))) return false;
    }
    return true;
}

inline bool validRequest(const char* url, const char* sha, size_t size, size_t capacity) {
    if (!url || strncmp(url, "https://", 8) != 0 || !validSha256(sha) ||
        size == 0 || size > capacity) return false;
    const char* host = url + 8;
    const char* slash = strchr(host, '/');
    if (!*host || *host == '/' || *host == '?' || *host == '#') return false;
    const char* at = strchr(host, '@');
    return !at || (slash && at > slash);
}

inline bool complete(size_t written, size_t expected) {
    return expected > 0 && written == expected;
}

inline bool timedOut(uint32_t now, uint32_t lastData) {
    return static_cast<uint32_t>(now - lastData) >= 60000u;
}
}
