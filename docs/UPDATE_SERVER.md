# Update server and fallback

## Incident and recovery

On 2026-11-08, GitHub latest and versioned release download URLs returned HTTP 504 from both Windows and the SSH server. The GitHub releases API still returned 200 and its asset endpoint downloaded the correct files. This reproduced the phone's failure independently of its network.

The signed v1.1.4 APK was copied to the project's HTTPS server and checked against its published SHA-256 and original signing certificate. Existing apps have a compiled GitHub URL, so a bootstrap installation is required while that endpoint is unavailable. Install over the existing app to retain its data:

https://systemdeveloper.ddns.net/human-radar/updates/latest.apk

## Android behavior from v1.1.5

App, firmware and bridge manifest checks try the configured project GitHub URL, then the matching HTTPS mirror if fetching or validation fails. A custom update channel never silently falls back to the production project. Both failures are included in the error details.

If a project APK download fails after a successful manifest check, the downloader tries the server's latest APK and verifies it against the originally selected manifest's checksum. A mirror version mismatch is rejected; run Check Update again if the mirror has advanced since the original check. The error dialog also offers a direct signed APK download link.

## Mirror publication

Public root: `/var/www/human-radar-updates`; state/lock/staging: `/var/lib/human-radar-updates`.

`tools/sync-update-mirror.sh` fetches public release metadata and assets through the [GitHub asset API](https://docs.github.com/en/rest/releases/assets). It checks versions, version codes, sizes, SHA-256 and ESP32 partition limits before switching the `current` symlink. Manifest and APK links resolve through that snapshot. Failed downloads retain the previous snapshot; older version codes cannot replace the current release.

Nginx serves only `/human-radar/updates/` using `deploy/nginx-human-radar-updates.conf`, included in the existing hostname's HTTPS server block. The original site remains available. A backup of the original site configuration is under `/root/project/MQTT_Server/nginx-backups/`.

## Operation

Install the service/timer from `deploy/` into `/etc/systemd/system/` after syncing the repository. The timer runs every five minutes. To refresh immediately after publishing:

```sh
systemctl start human-radar-update-mirror.service
systemctl status human-radar-update-mirror.timer
journalctl -u human-radar-update-mirror.service -n 30 --no-pager
```

## Validation

- Mirror fault tests: publish valid release, reject corrupt APK without replacing current, publish next valid release, reject downgrade.
- Android: simulated 504 followed by live HTTPS mirror and real APK download/checksum verification; actual manual UpdateManager check; Radar touch/fullscreen/autofill regressions.
- No firmware was flashed to the offline ESP32 during this incident.

Release v1.1.5 and automatic timer delivery are pending final checks.
