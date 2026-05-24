#include "bridge_mqtt.h"
#include "bridge_ota.h"

#include <WiFi.h>
#include <WiFiClient.h>
#include <WiFiClientSecure.h>
#include <Preferences.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

namespace {

constexpr uint16_t MQTT_BUFFER_SIZE = 4096;
constexpr uint32_t RECONNECT_RETRY_MS = 5000;
constexpr uint32_t STATUS_REPUBLISH_MS = 30000;

WiFiClient        g_plainClient;
WiFiClientSecure  g_tlsClient;
PubSubClient      g_mqtt;

char g_devName[33] = "HumanRadar";
char g_fwVersion[16] = "0.0.0";
char g_topicStatus[96];
char g_topicInfo[96];
char g_topicCmd[96];
char g_topicCmdAck[96];

bool g_useTls = false;
uint32_t g_lastConnectAttempt = 0;
uint32_t g_lastStatusPub = 0;

void buildTopics() {
    snprintf(g_topicStatus,  sizeof(g_topicStatus),  "humanradar/%s/status",  g_devName);
    snprintf(g_topicInfo,    sizeof(g_topicInfo),    "humanradar/%s/info",    g_devName);
    snprintf(g_topicCmd,     sizeof(g_topicCmd),     "humanradar/%s/cmd",     g_devName);
    snprintf(g_topicCmdAck,  sizeof(g_topicCmdAck),  "humanradar/%s/cmd/ack", g_devName);
}

void publishInfo() {
    char payload[256];
    snprintf(payload, sizeof(payload),
        "{\"ip\":\"%s\",\"fw\":\"%s\",\"name\":\"%s\",\"mac\":\"%s\",\"bridge\":1}",
        WiFi.localIP().toString().c_str(),
        g_fwVersion,
        g_devName,
        WiFi.macAddress().c_str());
    g_mqtt.publish(g_topicInfo, (const uint8_t*)payload, strlen(payload), true);
    Serial.printf("[bridge-mqtt] Published info: %s\n", payload);
}

void handleCmd(char* topic, byte* payload, unsigned int length) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, payload, length);
    if (err) {
        Serial.printf("[bridge-mqtt] cmd JSON parse error: %s\n", err.c_str());
        return;
    }
    const char* cmd       = doc["cmd"] | "";
    const char* requestId = doc["request_id"] | "";

    if (strcmp(cmd, "ota_pull") == 0) {
        const char* url = doc["url"] | "";
        if (strlen(url) == 0) {
            char ack[160];
            snprintf(ack, sizeof(ack),
                "{\"request_id\":\"%s\",\"status\":\"error\","
                "\"message\":\"missing url\"}", requestId);
            g_mqtt.publish(g_topicCmdAck, ack);
            return;
        }
        // Ack BEFORE starting download so the app knows the bridge got it
        // — the download itself takes 30+ seconds and might trigger the
        // app's verify timeout otherwise.
        char ack[160];
        snprintf(ack, sizeof(ack),
            "{\"request_id\":\"%s\",\"status\":\"ok\",\"cmd\":\"ota_pull\","
            "\"phase\":\"starting\"}", requestId);
        g_mqtt.publish(g_topicCmdAck, ack);
        delay(50);
        bridgeOtaPull(g_mqtt, g_topicCmdAck, requestId, url);
    } else if (strcmp(cmd, "restart") == 0) {
        char ack[128];
        snprintf(ack, sizeof(ack),
            "{\"request_id\":\"%s\",\"status\":\"ok\",\"cmd\":\"restart\"}",
            requestId);
        g_mqtt.publish(g_topicCmdAck, ack);
        delay(500);
        ESP.restart();
    } else if (strcmp(cmd, "health") == 0) {
        // Surface the fact this is the bridge so the app's health-check
        // panel knows the device is awaiting OTA.
        char ack[256];
        snprintf(ack, sizeof(ack),
            "{\"request_id\":\"%s\",\"status\":\"ok\",\"cmd\":\"health\","
            "\"device\":\"%s\",\"fw\":\"%s\",\"ip\":\"%s\","
            "\"uptime\":%lu,\"heap\":%lu,\"bridge\":1}",
            requestId, g_devName, g_fwVersion,
            WiFi.localIP().toString().c_str(),
            (unsigned long)(millis() / 1000UL),
            (unsigned long)ESP.getFreeHeap());
        g_mqtt.publish(g_topicCmdAck, ack);
    } else {
        char ack[192];
        snprintf(ack, sizeof(ack),
            "{\"request_id\":\"%s\",\"status\":\"error\","
            "\"message\":\"bridge: only ota_pull / restart / health supported\","
            "\"got\":\"%s\"}", requestId, cmd);
        g_mqtt.publish(g_topicCmdAck, ack);
    }
}

bool tryConnect() {
    Preferences p;
    p.begin("hradar", true);
    char host[65] = {0};
    char user[33] = {0};
    char pass[65] = {0};
    p.getString("mqtt_host", host, sizeof(host));
    uint16_t port = p.getUShort("mqtt_port", 1883);
    p.getString("mqtt_user", user, sizeof(user));
    p.getString("mqtt_pass", pass, sizeof(pass));
    uint8_t proto = p.getUChar("mqtt_proto", 2);  // 0=ws, 1=wss, 2=tcp, 3=tls
    p.end();

    if (strlen(host) == 0) {
        Serial.println("[bridge-mqtt] No broker host in NVS — sleeping");
        return false;
    }

    // The bridge only supports plain TCP and TLS. WebSocket transports
    // (proto 0/1) would need the WebSockets library we deliberately
    // stripped — fall back to TCP / TLS using the same host:port.
    g_useTls = (proto == 1 || proto == 3);
    if (g_useTls) {
        g_tlsClient.setInsecure();
        g_mqtt.setClient(g_tlsClient);
    } else {
        g_mqtt.setClient(g_plainClient);
    }
    g_mqtt.setServer(host, port);
    g_mqtt.setBufferSize(MQTT_BUFFER_SIZE);
    g_mqtt.setCallback(handleCmd);

    Serial.printf("[bridge-mqtt] Connecting %s:%d (%s) as '%s'\n",
                  host, port, g_useTls ? "TLS" : "TCP", g_devName);

    bool ok;
    if (strlen(user) > 0) {
        ok = g_mqtt.connect(g_devName, user, pass,
                            g_topicStatus, 1, true, "offline");
    } else {
        ok = g_mqtt.connect(g_devName, nullptr, nullptr,
                            g_topicStatus, 1, true, "offline");
    }
    if (!ok) {
        Serial.printf("[bridge-mqtt] connect failed (rc=%d)\n", g_mqtt.state());
        return false;
    }

    g_mqtt.publish(g_topicStatus, "online", true);
    publishInfo();
    g_mqtt.subscribe(g_topicCmd, 1);
    Serial.printf("[bridge-mqtt] Subscribed %s\n", g_topicCmd);
    g_lastStatusPub = millis();
    return true;
}

} // namespace

void bridgeMqttBegin(const char* deviceName, const char* fwVersion) {
    if (deviceName && deviceName[0]) {
        strncpy(g_devName, deviceName, sizeof(g_devName) - 1);
        g_devName[sizeof(g_devName) - 1] = '\0';
    }
    if (fwVersion && fwVersion[0]) {
        strncpy(g_fwVersion, fwVersion, sizeof(g_fwVersion) - 1);
        g_fwVersion[sizeof(g_fwVersion) - 1] = '\0';
    }
    buildTopics();
    tryConnect();
}

void bridgeMqttLoop() {
    if (!g_mqtt.connected()) {
        uint32_t now = millis();
        if (now - g_lastConnectAttempt >= RECONNECT_RETRY_MS) {
            g_lastConnectAttempt = now;
            tryConnect();
        }
        return;
    }
    g_mqtt.loop();

    // Republish info periodically so the app's discovery feed keeps the
    // bridge's fw label fresh even if the retained /info gets purged.
    uint32_t now = millis();
    if (now - g_lastStatusPub >= STATUS_REPUBLISH_MS) {
        publishInfo();
        g_lastStatusPub = now;
    }
}
