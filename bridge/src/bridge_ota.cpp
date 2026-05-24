#include "bridge_ota.h"

#include <Arduino.h>
#include <HTTPClient.h>
#include <Update.h>
#include <WiFiClient.h>
#include <WiFiClientSecure.h>

namespace {

constexpr int LED_PIN = 26;

void blinkBusy() {
    static uint32_t last = 0;
    static bool on = false;
    uint32_t now = millis();
    if (now - last >= 250) {
        on = !on;
        digitalWrite(LED_PIN, on ? HIGH : LOW);
        last = now;
    }
}

void publishFail(PubSubClient& mqtt, const char* ackTopic,
                 const char* requestId, const char* msg, int httpCode) {
    char ack[384];
    snprintf(ack, sizeof(ack),
        "{\"request_id\":\"%s\",\"status\":\"error\",\"cmd\":\"ota_pull\","
        "\"message\":\"%s\",\"http\":%d}", requestId, msg, httpCode);
    mqtt.publish(ackTopic, ack);
}

} // namespace

void bridgeOtaPull(PubSubClient& mqtt, const char* ackTopic,
                   const char* requestId, const char* url) {
    Serial.printf("[bridge-ota] Pull %s\n", url);
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);

    WiFiClientSecure tls;
    tls.setInsecure();
    WiFiClient plain;

    HTTPClient http;
    http.setUserAgent("HumanRadarBridge/1.0");
    http.setFollowRedirects(HTTPC_STRICT_FOLLOW_REDIRECTS);
    http.setReuse(false);
    http.setTimeout(60000);

    bool isHttps = (strncmp(url, "https://", 8) == 0);
    bool ok = isHttps ? http.begin(tls, url) : http.begin(plain, url);
    if (!ok) {
        Serial.println("[bridge-ota] http.begin failed");
        digitalWrite(LED_PIN, LOW);
        publishFail(mqtt, ackTopic, requestId, "http.begin failed", 0);
        return;
    }

    int code = http.GET();
    if (code != HTTP_CODE_OK) {
        Serial.printf("[bridge-ota] HTTP %d\n", code);
        http.end();
        digitalWrite(LED_PIN, LOW);
        publishFail(mqtt, ackTopic, requestId, "HTTP GET failed", code);
        return;
    }

    int total = http.getSize();
    Serial.printf("[bridge-ota] Downloading %d bytes\n", total);

    if (!Update.begin(total > 0 ? (size_t)total : UPDATE_SIZE_UNKNOWN)) {
        Serial.printf("[bridge-ota] Update.begin failed: %s\n",
                      Update.errorString());
        http.end();
        digitalWrite(LED_PIN, LOW);
        publishFail(mqtt, ackTopic, requestId, Update.errorString(), 0);
        return;
    }

    WiFiClient* stream = http.getStreamPtr();
    const size_t bufSize = 1024;
    uint8_t buf[bufSize];
    size_t written = 0;
    uint32_t lastReport = millis();

    while (http.connected() && (total <= 0 || (int)written < total)) {
        size_t avail = stream->available();
        if (avail == 0) { delay(1); blinkBusy(); continue; }
        size_t toRead = avail > bufSize ? bufSize : avail;
        int got = stream->readBytes(buf, toRead);
        if (got <= 0) continue;
        size_t wrote = Update.write(buf, got);
        if (wrote != (size_t)got) {
            Serial.printf("[bridge-ota] Update.write short %u/%d\n",
                          (unsigned)wrote, got);
            break;
        }
        written += wrote;
        blinkBusy();

        // Periodic progress publish — every ~2 seconds.
        if (millis() - lastReport > 2000 && total > 0) {
            int pct = (int)((written * 100ULL) / (size_t)total);
            char prog[160];
            snprintf(prog, sizeof(prog),
                "{\"request_id\":\"%s\",\"status\":\"progress\","
                "\"cmd\":\"ota_pull\",\"pct\":%d,\"bytes\":%u}",
                requestId, pct, (unsigned)written);
            mqtt.publish(ackTopic, prog);
            mqtt.loop();  // service callbacks
            lastReport = millis();
        }
        yield();
    }
    http.end();

    if (!Update.end(true)) {
        Serial.printf("[bridge-ota] Update.end failed: %s\n",
                      Update.errorString());
        digitalWrite(LED_PIN, LOW);
        publishFail(mqtt, ackTopic, requestId, Update.errorString(), 0);
        return;
    }

    Serial.printf("[bridge-ota] %u bytes flashed, restarting\n",
                  (unsigned)written);

    char ack[256];
    snprintf(ack, sizeof(ack),
        "{\"request_id\":\"%s\",\"status\":\"ok\",\"cmd\":\"ota_pull\","
        "\"bytes\":%u,\"restarting\":true}", requestId, (unsigned)written);
    mqtt.publish(ackTopic, ack);
    mqtt.loop();
    delay(500);

    // 4× quick blink so the operator sees the handoff
    for (int i = 0; i < 4; i++) {
        digitalWrite(LED_PIN, HIGH); delay(150);
        digitalWrite(LED_PIN, LOW);  delay(150);
    }

    ESP.restart();
}
