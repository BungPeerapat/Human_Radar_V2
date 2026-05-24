#pragma once
#include <Arduino.h>

// Bridge MQTT client — connect to the broker stored in NVS, subscribe
// to humanradar/<deviceName>/cmd, handle ota_pull + restart only.
//
// Re-uses the same broker / topic shape as the full firmware so the
// Android app can talk to the bridge using the existing MqttService
// + sendCommandToWithExtras() — no app changes required.
void bridgeMqttBegin(const char* deviceName, const char* fwVersion);
void bridgeMqttLoop();
