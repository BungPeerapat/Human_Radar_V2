# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Hybrid embedded + mobile project: **ESP32 firmware** reads HLK-LD2450 mmWave radar data (3 targets, 10 Hz), serves a real-time web radar visualization, and publishes data via MQTT. An **Android app** subscribes to the same MQTT topics and renders a companion radar UI with logs and config.

## Repository Structure

- `Human_Radar_V2/` — Arduino IDE project (primary, ESP32-WROOM32). All `.ino`/`.cpp`/`.h` sit in one flat folder.
- `firmware/` — PlatformIO mirror of the same firmware. Layout: `include/` for headers, `src/` for implementations, with `src/radar/` holding `radar_driver.cpp` and `radar_parser.cpp`. Entry point is `src/main.cpp` (the Arduino `setup()`/`loop()` are duplicated here).
- `app/` — Android application (Java, Gradle Kotlin DSL).
- Root `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` — Android Gradle project.

**Important — keep the two firmware trees in sync.** Arduino IDE (`Human_Radar_V2/`) and PlatformIO (`firmware/`) share the same logical source but live in different layouts. When editing firmware, mirror every change:
- header changes → both `Human_Radar_V2/<name>.h` and `firmware/include/<name>.h`
- `radar_driver.cpp` / `radar_parser.cpp` → `Human_Radar_V2/` flat AND `firmware/src/radar/`
- other `.cpp` → `Human_Radar_V2/` flat AND `firmware/src/`
- `Human_Radar_V2.ino` `setup()`/`loop()` ↔ `firmware/src/main.cpp`

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

PlatformIO `lib_deps` (in `firmware/platformio.ini`): `links2004/WebSockets@^2.4.0`, `knolleary/PubSubClient@^2.8`, `bblanchon/ArduinoJson@^7.0.0`. Build flags include `-DBOARD_HAS_NO_PSRAM`.

### ESP32 Firmware (Arduino IDE)
Open `Human_Radar_V2/Human_Radar_V2.ino`. Board: "ESP32 Dev Module". Required libraries (via Library Manager):
- WebSockets by Markus Sattler (v2.4.0+)
- PubSubClient by Nick O'Leary (v2.8+)
- ArduinoJson by Benoit Blanchon (v7.0+)

### Android App
```bash
./gradlew assembleDebug                          # Build debug APK
./gradlew test                                   # Unit tests (JVM)
./gradlew connectedAndroidTest                   # Instrumented tests (device required)
./gradlew :app:testDebugUnitTest --tests <FQCN>  # Run a single unit test class/method
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
| `wifi_config.h` | Compile-time WiFi defaults (AP SSID: HumanRadar, open — no password) |

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

- Package: `com.example.radarhumanapplication`, Java 11
- `compileSdk 35`, `targetSdk 34`, `minSdk 26`
- `usesCleartextTraffic="true"` — required to connect to the ESP32 over plain TCP/WS on the local AP. Don't change without updating the firmware to TLS.
- Version catalog at `gradle/libs.versions.toml`. Key deps: `com.hivemq:hivemq-mqtt-client` (MQTT 3 async), `com.google.code.gson:gson`, AndroidX appcompat/material/constraintlayout.
- Single `MainActivity` hosts four fragments:
  - `DashboardFragment` — overview / connection state
  - `RadarFragment` — live radar plot (custom `RadarView` canvas mirroring the firmware's web canvas)
  - `LogsFragment` + `LogAdapter` — MQTT message log
  - `ConfigFragment` — broker host/port/credentials and device name
- `MqttService` is a process-wide singleton (`getInstance()`) wrapping a `Mqtt3AsyncClient`. Fragments share state through it; do not instantiate per-fragment clients.
- `META-INF/INDEX.LIST` and `io.netty.versions.properties` are excluded from packaging to resolve duplicates pulled in by HiveMQ/Netty.

### App update system (in-app updater)

- Updater code lives in `app/src/main/java/com/example/radarhumanapplication/update/`:
  `UpdateInfo` (manifest model), `UpdateManager` (singleton check + 6h debounce + skip-version),
  `ApkDownloader` (DownloadManager + SHA-256 verify), `ApkInstaller` (FileProvider + ACTION_VIEW),
  `UpdateDialog` (release-notes prompt + progress).
- `MainActivity.onCreate` triggers `UpdateManager.checkAuto`. Config tab has a manual
  "Check for updates" button.
- Manifest URL is injected via `BuildConfig.UPDATE_MANIFEST_URL` from gradle property /
  env `UPDATE_MANIFEST_URL`. Placeholder `https://REPLACE_ME...` makes the updater
  short-circuit with `Status.DISABLED`.
- `versionCode` / `versionName` are read from env (`APP_VERSION_CODE`, `APP_VERSION_NAME`)
  in `app/build.gradle.kts`. CI derives them from the git tag and `git rev-list --count HEAD`.
- Release signing is keystore-based and env-driven (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`). When env vars are absent the release build skips signing.
- `REQUEST_INSTALL_PACKAGES` permission and a FileProvider (`${applicationId}.fileprovider`,
  paths in `res/xml/file_provider_paths.xml`) are required for the install handoff.
- Release flow: push `vX.Y.Z` tag → `.github/workflows/release.yml` builds, signs, hashes,
  publishes APK + `update.json` to GitHub Releases, and pushes `update.json` to `gh-pages`.
  Full setup + secret list in `tools/RELEASING.md`.

## Key Conventions

- Web UI pages are stored as `const char[] PROGMEM` in header files (`web_page.h`, `web_settings.h`)
- No external JSON library — uses simple `indexOf`/`substring` parsing for flat JSON
- WiFi falls back to AP mode if STA connection times out
- NVS namespace: `"hradar"`
- ESP32-WROOM32 specific: no PSRAM, GPIO16/17 safe for UART2, `setRxBufferSize(512)`
