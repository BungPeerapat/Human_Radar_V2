#pragma once
#include <ArduinoJson.h>

// Keep parsing separate from mutation so malformed/incorrectly typed JSON
// cannot erase connectivity settings. Accept normal JSON spacing and escapes.
class ConfigJson {
    JsonDocument document;
    bool parsed;
public:
    explicit ConfigJson(const char* body) : parsed(false) {
        parsed = !deserializeJson(document, body) && document.is<JsonObject>();
    }
    bool valid() const { return parsed; }
    bool has(const char* key) const { return !document[key].isNull(); }
    const char* string(const char* key) const { return document[key] | ""; }
    int number(const char* key, int fallback) const {
        return document[key].is<int>() ? document[key].as<int>() : fallback;
    }
    bool fieldTypesValid() const {
        if (!parsed) return false;
        for (const char* key : {"ws", "wp", "mh", "mu", "mpp", "dn"}) {
            if (has(key) && !document[key].is<const char*>()) return false;
        }
        for (const char* key : {"wm", "me", "mr", "mp", "ar", "pi", "ud", "tt", "mt", "sn"}) {
            if (has(key) && !document[key].is<int>()) return false;
        }
        if (has("wm") && (number("wm", -1) < 0 || number("wm", -1) > 1)) return false;
        if (has("me") && (number("me", -1) < 0 || number("me", -1) > 1)) return false;
        if (has("mr") && (number("mr", -1) < 0 || number("mr", -1) > 3)) return false;
        if (has("mp") && (number("mp", 0) < 1 || number("mp", 0) > 65535)) return false;
        return true;
    }
};
