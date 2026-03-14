# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Hybrid embedded + mobile project: **ESP32 firmware** reads HLK-LD2450 mmWave radar data (3 targets, 10 Hz), serves a real-time web radar visualization, and publishes data via MQTT. An **Android app** (early stage) will be the companion client.

## Repository Structure

- `Human_Radar_V2/` — Arduino IDE project (primary, ESP32-WROOM32)
- `firmware/` — PlatformIO mirror of the same firmware (backup build system)
- `app/` — Android application (Java, Gradle Kotlin DSL)
- Root `build.gradle.kts`, `settings.gradle.kts` — Android Gradle project

**Important:** Arduino IDE and PlatformIO share the same source. When editing firmware, update both `Human_Radar_V2/` and `firmware/` (include→headers, src→implementations).

## Build Commands

### ESP32 Firmware (PlatformIO)
```bash
# Build
"$HOME/.platformio/penv/Scripts/pio.exe" run -d firmware

# Upload
"$HOME/.platformio/penv/Scripts/pio.exe" run -d firmware -t upload

# Serial monitor
"$HOME/.platformio/penv/Scripts/pio.exe" device monitor -d firmware -b 115200
```

### ESP32 Firmware (Arduino IDE)
Open `Human_Radar_V2/Human_Radar_V2.ino`. Board: "ESP32 Dev Module". Required libraries (via Library Manager):
- WebSockets by Markus Sattler (v2.4.0+)
- PubSubClient by Nick O'Leary (v2.8+)

### Android App
```bash
./gradlew assembleDebug        # Build debug APK
./gradlew test                 # Unit tests
./gradlew connectedAndroidTest # Instrumented tests
```

## Firmware Architecture

Data flow: `LD2450 → UART2 → RadarDriver (state machine) → RadarParser → WebSocket broadcast + MQTT publish`

| Module | Role |
|---|---|
| `radar_types.h` | Protocol constants, RadarTarget/RadarFrame structs, LD2450 custom sign decoding |
| `radar_parser` | Pure stateless function: 30-byte buffer → RadarFrame |
| `radar_driver` | UART state machine: FIND_HEADER → READ_BODY → validate footer |
| `config_manager` | NVS persistent storage for WiFi/MQTT/device settings |
| `web_server` | WiFi AP/STA setup, HTTP routes (`/`, `/settings`, `/api/config`), WebSocket on port 81 |
| `web_page.h` | Canvas-based radar visualization (PROGMEM HTML/JS) |
| `web_settings.h` | Settings page with WiFi mode, MQTT config, toggle switch (PROGMEM HTML/JS) |
| `mqtt_client` | Multi-protocol MQTT (ws/wss/tcp/tls) via PubSubClient + WSMqttClient bridge |
| `logger.h` | Serial debug output (header-only) |
| `wifi_config.h` | Compile-time WiFi defaults (AP: HumanRadar/radar1234) |

### HLK-LD2450 Protocol Details
- Baud: 256000, UART2 (GPIO16 RX, GPIO17 TX)
- Frame: 30 bytes — header `AA FF 03 00` + 3×8B targets + footer `55 CC`
- Custom sign encoding (NOT two's complement): bit15=1→positive, bit15=0→negative
- Target fields: X(2B mm), Y(2B mm), Speed(2B cm/s), DistRes(2B mm), all little-endian

### MQTT Topics
- `humanradar/{deviceName}/targets` — JSON at 10 Hz
- `humanradar/{deviceName}/status` — LWT online/offline (retained)

### Config API (JSON)
- `GET /api/config` — keys: `wm` (wifi mode), `ws`/`wp` (ssid/pass), `me` (mqtt enable), `mr` (mqtt protocol 0-3), `mh`/`mp`/`mu`/`mpp` (mqtt host/port/user/pass), `ms` (mqtt status), `dn` (device name), `ip`
- `POST /api/config` — same keys, triggers ESP.restart()

## Android App

- Package: `com.example.radarhumanapplication`
- Java 11, compileSdk/targetSdk 34, minSdk 34
- Currently scaffold only (EdgeToEdge + ConstraintLayout)
- Version catalog at `gradle/libs.versions.toml`

## Key Conventions

- Web UI pages are stored as `const char[] PROGMEM` in header files (`web_page.h`, `web_settings.h`)
- No external JSON library — uses simple `indexOf`/`substring` parsing for flat JSON
- WiFi falls back to AP mode if STA connection times out
- NVS namespace: `"hradar"`
- ESP32-WROOM32 specific: no PSRAM, GPIO16/17 safe for UART2, `setRxBufferSize(512)`
