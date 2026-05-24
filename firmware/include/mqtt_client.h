#ifndef MQTT_CLIENT_H
#define MQTT_CLIENT_H

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <WebSocketsClient.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include "config_manager.h"
#include "radar_types.h"

// ============================================================================
// MQTT Protocol Types
// ============================================================================
enum MqttProto : uint8_t {
    MQTT_PROTO_WS    = 0,
    MQTT_PROTO_WSS   = 1,
    MQTT_PROTO_TCP   = 2,
    MQTT_PROTO_TLS   = 3,
};

// ============================================================================
// MQTT Configuration
// ============================================================================
static constexpr uint32_t MQTT_RECONNECT_INTERVAL = 5000;
static constexpr uint16_t MQTT_BUFFER_SIZE        = 1024;   // increased for config ACK
static constexpr uint32_t MQTT_WS_CONNECT_TIMEOUT = 8000;
static constexpr uint16_t MQTT_WS_RINGBUF_SIZE    = 1024;

// ============================================================================
// WSMqttClient: WebSocket transport wrapper for PubSubClient
// ============================================================================
class WSMqttClient : public Client {
public:
    void configure(bool useTLS, const char* path = "/mqtt");

    int connect(IPAddress ip, uint16_t port) override;
    int connect(const char* host, uint16_t port) override;
    size_t write(uint8_t b) override;
    size_t write(const uint8_t* buf, size_t size) override;
    int available() override;
    int read() override;
    int read(uint8_t* buf, size_t size) override;
    int peek() override;
    void flush() override {}
    void stop() override;
    uint8_t connected() override;
    operator bool() override;

    void wsLoop() { _ws.loop(); }

private:
    WebSocketsClient _ws;
    bool _useTLS = false;
    const char* _path = "/mqtt";
    volatile bool _connected = false;

    uint8_t _ring[MQTT_WS_RINGBUF_SIZE];
    volatile size_t _head = 0;
    volatile size_t _tail = 0;

    size_t ringAvailable() const;
    void ringPush(const uint8_t* data, size_t len);

    static WSMqttClient* _self;
    static void onEvent(WStype_t type, uint8_t* payload, size_t length);
};

// Forward declare LogEntry
struct LogEntry;

// ============================================================================
// MqttRadarClient: Full MQTT client with publish, subscribe, config, commands
//
// Topics:
//   humanradar/{name}/targets      <- publish (radar data)
//   humanradar/{name}/status       <- publish (LWT: online/offline)
//   humanradar/{name}/log          <- publish (log forwarding)
//   humanradar/{name}/config       <- subscribe (receive config from app)
//   humanradar/{name}/config/ack   <- publish (config response)
//   humanradar/{name}/cmd          <- subscribe (receive commands from app)
//   humanradar/{name}/cmd/ack      <- publish (command response)
// ============================================================================
class MqttRadarClient {
public:
    void begin();
    void loop();
    void publishFrame(const RadarFrame& frame, uint32_t frameCount, uint32_t errorCount);
    void publishLog(const LogEntry& entry);

    bool isConnected() { return _mqtt.connected(); }
    bool isEnabled() const;
    const char* getStatusText();
    const char* getProtoText() const;

private:
    WiFiClient       _tcpClient;
    WiFiClientSecure _tlsClient;
    WSMqttClient     _wsClient;

    PubSubClient _mqtt;

    uint32_t _lastReconnectAttempt = 0;
    uint32_t _lastPublish = 0;
    uint32_t _publishCount = 0;
    bool     _wasConnected = false;
    bool     _initialized = false;
    uint8_t  _proto = MQTT_PROTO_TCP;

    char _topicTargets[96];
    char _topicStatus[96];
    char _topicInfo[96];
    char _topicLog[96];
    char _topicConfig[96];
    char _topicConfigAck[96];
    char _topicCmd[96];
    char _topicCmdAck[96];

    void buildTopics();
    void publishInfo();
    void setupTransport();
    bool tryConnect();
    void subscribeAll();

    static void mqttCallback(char* topic, byte* payload, unsigned int length);
    void handleMessage(const char* topic, const uint8_t* payload, unsigned int length);
    void handleConfig(const uint8_t* payload, unsigned int length);
    void handleCommand(const uint8_t* payload, unsigned int length);

    void cmdRestart(const char* requestId);
    void cmdFactoryReset(const char* requestId);
    void cmdGetLogBuffer(const char* requestId, int limit);
    void cmdSetLogLevel(const char* requestId, const char* level);
    void cmdHealth(const char* requestId);
    void cmdOtaPull(const char* requestId, const char* url);
};

extern MqttRadarClient mqttClient;

#endif // MQTT_CLIENT_H
