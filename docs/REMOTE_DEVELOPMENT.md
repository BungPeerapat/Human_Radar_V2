# Remote Development Server

Configured on 2026-11-08. The server also hosts other applications; keep changes scoped to this project.

## SSH access

- Host: `203.159.94.20` (`systemdeveloper.ddns.net`), port `22`, user `root`.
- On this Windows account, connect using `ssh human-radar-uat` (Tailscale `100.77.46.79`).
- Public SSH fallback remains `203.159.94.20:22`.
- SSH config: `%USERPROFILE%\.ssh\config`.
- Dedicated private key: `%USERPROFILE%\.ssh\human_radar_uat_rsa`. Do not commit or share it.
- The supplied SSH password has not been saved to project files. Future connections use the dedicated key.
- Host key (ED25519): `AAAAC3NzaC1lZDI1NTE5AAAAIK5F84ytkz3FNJSMhEzSMMOkQLrUG0iSjQPyT7JWLEQs`.

## Repository

Remote checkout: `/root/project/Human_Radar_V2`.

Origin: `https://github.com/BungPeerapat/Human_Radar_V2.git`.

Branch: `dev`; initial commit: `cea5c529c02867803d6906f743337742db56f5df`.

```sh
cd /root/project/Human_Radar_V2
git status
git pull --ff-only origin dev
```

The initial clone contained committed Git content. Current migration changes and task files are synchronized separately; inspect the actual branch/commit before editing. PlatformIO is configured in `/root/project/.venv-human-radar`, and normal/bridge firmware builds passed on Linux. GitHub push authentication and a Linux Android SDK remain unconfigured. Hardware USB access is on Windows; working network OTA has been verified on the ESP32. See `docs/SERVER_HANDOFF.md`.

## MQTT connection

Deployment directory: `/root/project/MQTT_Server` (outside the repository).

| Transport | Host | Port | Details |
| --- | --- | --- | --- |
| MQTT TLS | `systemdeveloper.ddns.net` | `8883` | Recommended for ESP32 and Android |
| Secure WebSocket | `systemdeveloper.ddns.net` | `9002` | Path `/mqtt` |
| Plain MQTT | `127.0.0.1` | `1883` | Server-local access only |
| Plain WebSocket | `127.0.0.1` | `9001` | Server-local access only |

Username: `humanradar`. The generated password and connection settings are in `/root/project/MQTT_Server/credentials.json`, readable only by root. Retrieve them over SSH when configuring a device; never commit them. Public TLS clients must use the hostname and validate the certificate. The account can read/write only `humanradar/#`; anonymous access is disabled.

## Operations

```sh
docker ps --filter name=human-radar-mqtt
docker logs --tail 100 human-radar-mqtt
docker restart human-radar-mqtt
systemctl status human-radar-mqtt-cert-sync.timer
systemctl start human-radar-mqtt-cert-sync.service
```

Mosquitto 2.1.2 uses a pinned Docker image digest recorded in the deployment README. Docker restarts the broker automatically. Data persists in `data/`; logs rotate at 10 MB across three files. Container limits are 128 MB RAM and 0.5 CPU; the host does not support Docker swap limits. Back up configuration, credentials, and data securely.

An hourly systemd timer copies the existing Let's Encrypt certificate and reloads the broker. Certificate issuance and renewal remain managed by the server's existing Certbot setup. Only public TCP ports `8883` and `9002` were added to UFW. Existing web services were not reconfigured.

## OTA

The repository implements MQTT-triggered firmware download (`ota_pull`). A deployed ESP32 needs compatible firmware, a suitable OTA partition, working network access, and a reachable firmware URL. Installing MQTT alone does not establish a firmware hosting or release pipeline. USB may still be needed for initial provisioning or recovery.

Broker authentication, external TLS/WSS messaging, topic authorization, certificate reload, and persistence across a broker restart were checked. Physical ESP32 OTA, rollback/bridge recovery, and real Android emulator integration have since passed. Remaining power-cut and UART-target acceptance gates are recorded in `docs/MQTT_OTA_TEST_RESULTS.md`.
