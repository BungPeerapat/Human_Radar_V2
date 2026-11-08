#include "bridge_ota.h"
#include "ota_download.h"
#include <ArduinoJson.h>

void bridgeOtaPull(PubSubClient& mqtt, const char* ackTopic,
                   const char* requestId, const char* url,
                   const char* sha256, size_t sizeBytes) {
    pinMode(26, OUTPUT);
    auto ack = [&](const char* status, const char* message, size_t written) {
        JsonDocument doc;
        doc["request_id"] = requestId;
        doc["cmd"] = "ota_pull";
        doc["status"] = status;
        doc["message"] = message;
        doc["bytes"] = written;
        if (strcmp(status, "progress") == 0) doc["pct"] = sizeBytes ? written * 100u / sizeBytes : 0;
        if (strcmp(status, "ok") == 0) doc["restarting"] = true;
        char payload[512];
        serializeJson(doc, payload, sizeof(payload));
        mqtt.publish(ackTopic, payload);
    };
    const char* error = downloadVerifiedOta(url, sha256, sizeBytes,
        []() { digitalWrite(26, (millis() / 250u) % 2u ? HIGH : LOW); },
        [&](size_t written, size_t) { ack("progress", "Downloading and verifying", written); });
    digitalWrite(26, LOW);
    if (error) { ack("error", error, 0); return; }
    ack("ok", "SHA-256 verified; restarting", sizeBytes);
    delay(500);
    ESP.restart();
}
