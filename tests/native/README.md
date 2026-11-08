# Native firmware tests

On Linux, after installing the pinned PlatformIO dependencies:

```sh
source /root/project/.venv-human-radar/bin/activate
pio pkg install -d firmware
bash tools/test-native.sh
```

Requires `g++`, `gcov`, and OpenSSL development headers/library. The test build stays in a unique `/tmp/human-radar-native.*` directory; it does not write generated objects or coverage files into the repository.

- `ota_policy_test.cpp`: request metadata, slot size, exact completion, wrap-safe timeout, and project/bridge endpoint policy.
- `config_json_test.cpp`: standard JSON whitespace/escaping, partial updates, invalid field types, and connectivity field ranges; uses the actual pinned ArduinoJson library.
- `ota_download_test.cpp`: compiles the production downloader against deterministic fake HTTP/TLS/flash APIs and OpenSSL SHA-256. Covers good transfer, bad hash, disconnected/stalled body, missing/mismatched length, slot overflow, HTTP error, wrong image magic, begin/write/end failure, timeout settings, and watchdog subscription restoration.

The script also checks the Arduino/PlatformIO/shared bridge files modified for migration remain identical. `coverage.txt` and `.gcov` files are in the printed artifact directory. These tests do not emulate the ESP32 bootloader, flash power failure, WiFi stack, or actual certificate validation. Hardware acceptance is recorded separately in `docs/MQTT_OTA_TEST_RESULTS.md`.
