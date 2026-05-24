#pragma once
#include <Arduino.h>
#include <PubSubClient.h>

// Download a .bin from {@code url} and stream it into Update.h.
// Publishes progress + completion / error JSON onto {@code ackTopic}
// (the device's humanradar/<name>/cmd/ack) using the supplied MQTT
// client. Calls ESP.restart() on success — does not return in that
// path.
void bridgeOtaPull(PubSubClient& mqtt, const char* ackTopic,
                   const char* requestId, const char* url);
