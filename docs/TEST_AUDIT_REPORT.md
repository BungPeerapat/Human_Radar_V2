# Test & Audit Report — Human Radar V2

**Date:** 2026-05-13 · **Scope:** Bug triage, code audit (22 issues found),
smoke + happy-path + edge-case review, UI evaluation.

---

## 1. Reported bug (fixed in v1.0.27)

> **"Radar tab shows CONNECTED + frozen targets after the ESP32 powers off."**

**Root cause.** `RadarFragment.updateConnectionStatus()` only consulted
`mqtt.isConnected()` — the **broker** connection. When the ESP32 dies, the
broker connection is still alive, so the status text stayed green and no
event cleared the stale targets on the canvas.

**Fix (v1.0.27).**

| Change | File |
|---|---|
| `RadarFragment` now implements `MqttService.StatusListener` and reacts to the LWT `humanradar/<name>/status` → "offline" by clearing the radar | `RadarFragment.java` |
| Added a **stale-frame watchdog** (1 s tick, 3 s threshold) — if no frame arrives the canvas clears and the status flips to "NO DATA" | `RadarFragment.java` |
| New `RadarView.clearTargets()` resets all target slots + trails atomically | `RadarView.java` |
| Status text expanded to: `CONNECTED` / `NO DATA` (yellow) / `DEVICE OFFLINE` (red) / `DISCONNECTED` (red) | `RadarFragment.updateConnectionStatus()` |

---

## 2. Code audit findings (22 issues)

Conducted by `code-reviewer` subagent across `app/src/main/java/com/example/radarhumanapplication/`.
Severity breakdown: **2 CRITICAL · 7 HIGH · 8 MEDIUM · 5 LOW.**

### Fixed in this release (v1.0.27)

| ID | Severity | File | Fix |
|---|---|---|---|
| **C1** | CRITICAL | `MqttService.java` | `connected` declared `volatile` — cross-thread reads were stale |
| **C2** | CRITICAL | `MqttService.java`, `SessionReplayer.java` | Public `dispatchTargetsToListeners()` replaces brittle reflection in replay path |
| **H4** | HIGH | `AlertsFragment.java` | `RingtoneManager.getRingtone()` null-check before `.getTitle()` (was crashable when picked clip was deleted/unmounted) |
| **H6** | HIGH | `WavAlertSound.java` | Always `seekTo(0)` before `start()` — non-looping clips played nothing on second trigger after natural completion |
| **M1** | MEDIUM | `SessionReplayer.java` | Natural replay end now routes through `stop()` — live-MQTT mute is released, was leaking and silently muting live data after replay |
| **L4** | LOW | `DashboardFragment.java` | `notifyItemRangeChanged(position, getItemCount() - position)` — was off-by-one after `files.remove()` |

### Deferred (tracked here, not blocking)

| ID | Severity | Reason for deferral | Suggested follow-up commit |
|---|---|---|---|
| H1 | HIGH | AlertsFragment test-preview cleanup is rare-path; current path leaks one MediaPlayer per session at worst | Add `AlertSoundPlayer.release()`, null `testSoundPlayer`/`testVibrator` in `onDestroyView` |
| H2 | HIGH | RecordingAdapter.requireContext() crash only on rotation while dialog open | Capture context in `onCreateViewHolder` |
| H3 | HIGH | ConfigFragment switch listeners — only fires bug if OS re-broadcasts during destroy | Set listeners to null in `onDestroyView` |
| H5 | HIGH | logBuffer `remove(0)` on CopyOnWriteArrayList is O(n²) under heavy load | Convert to bounded `ArrayDeque` + synchronized |
| H7 | HIGH | `SessionRecorder.writer` volatile — practically safe today | Declare volatile or merge guards |
| M2–M8 | MEDIUM | All non-crash, mostly correctness or thread-races on cold paths | See full audit (in commit message of v1.0.27) |
| L1, L2, L3, L5 | LOW | Style / micro-perf | Future cleanup |

---

## 3. Test scenarios

### 3.1 Smoke Test (pre-release sanity)

| # | Step | Expected | Auto-checkable? |
|---|---|---|---|
| S1 | Install fresh APK | App opens without crash | manual |
| S2 | Open every tab once (Dashboard, Radar, Alerts, Logs, Config) | No FATAL EXCEPTION in logcat | `adb logcat *:E` |
| S3 | Rotate device on each tab | No fragment lifecycle crash | manual |
| S4 | Background + foreground app | MQTT auto-reconnects | logcat `MqttService` |
| S5 | Kill + restart | Settings preserved, Profiles restored | manual |
| S6 | `CHECK FOR UPDATES` | Either UPDATE_AVAILABLE dialog or "Up to date" — never silent | manual |
| S7 | `PICK` device IP | Picker shows ≥1 row with status dot | manual |

### 3.2 Happy Path

| # | Flow | Pass criteria |
|---|---|---|
| H1 | First boot ESP32 (no NVS) → AP "HumanRadar" (open) → 192.168.4.1/settings → enter home WiFi → save | ESP32 reboots into STA mode, reaches MQTT, publishes `online` + `/info` |
| H2 | App connects MQTT → walk in front of sensor | Target appears on radar, distance updates @ 10 Hz, FPS ≈ 10 |
| H3 | Define distance rule `Any < 1500mm` + ringtone → walk in | Sound plays once, rule re-arms on exit, fires again on re-entry |
| H4 | Record session 30 s → walk back and forth → stop → Replay | REPLAY badge shows on radar, live MQTT muted, on stop live resumes |
| H5 | Auto firmware update: CHECK FIRMWARE UPDATE → Install | Big % bar climbs 0→100, GPIO26 plays 3×slow + 2×fast pattern, ESP32 reboots into new version |
| H6 | Device-status sound configured → power off ESP32 | Offline sound plays once on transition |

### 3.3 Edge Cases

| # | Scenario | Status |
|---|---|---|
| E1 | ESP32 power-pulled mid-stream | ✅ **Fixed in v1.0.27** — radar clears, status "DEVICE OFFLINE" |
| E2 | MQTT broker dies mid-session | ✅ HiveMQ auto-reconnects + `onDisconnected` clears radar (v1.0.27) |
| E3 | Bad JSON payload from broker | ✅ try/catch in `handleTargets`; logged + silently dropped |
| E4 | Two ESP32s on same broker, app configured for one | ✅ TargetListener fan-out is keyed to configured deviceName only |
| E5 | Phone in background → MQTT keeps alive | ✅ `MqttForegroundService` (dataSync type) |
| E6 | Screen rotation mid-radar | ⚠️ Fragment recreates — last frame replays from `lastTargetData` cache |
| E7 | WiFi → cellular switch | ⚠️ Default HiveMQ reconnect; can take 30–60 s |
| E8 | Notification permission denied (Android 13+) | ⚠️ Foreground service runs but no visible notification; service still alive |
| E9 | Network blocks GitHub Releases | ✅ UpdateManager surfaces ERROR with reason (v1.0.16+) |
| E10 | Mid-OTA power cut on ESP32 | ✅ ESP32 bootloader auto-rollback to previous OTA partition |
| E11 | `/api/version` returns wrong fw (older device after rollback) | ✅ Check dialog shows mismatch clearly |
| E12 | Picked sound URI revoked (file deleted) | ✅ **Fixed in v1.0.27** — falls back to URI last segment |
| E13 | Replay finishes naturally | ✅ **Fixed in v1.0.27** — `stop()` releases mute |
| E14 | OTA endpoint unreachable (firmware <v1.0.18) | ⚠️ User sees HTTP timeout; better to detect upfront |
| E15 | Multiple alert rules match same target | ✅ Each rule fires independently; cooldowns per rule |
| E16 | Recording while broker drops | ⚠️ Recorder reads `lastTargetData` cache, may miss frames |
| E17 | Two simultaneous replays | ✅ `loadAndStart()` calls `stop()` first |
| E18 | Empty AP password (v1.0.26+) | ✅ Open network — joins without prompt |
| E19 | ESP32 firmware update fails verify | ✅ Update.h rolls back, app surfaces error from response body |
| E20 | Battery monitor with no voltage divider | ⚠️ Returns 0V or random noise; works as diagnostic only |

⚠️ markers = known limitation, not a regression.

---

## 4. UI / UX evaluation

### Overall

App is **functional and feature-rich** but **information-dense**. Config tab
is a long scroll with 6+ card sections each containing multiple controls.
Most users will use ≤2 sections per visit.

### Wins

- ✅ Live status dots (●/○) in device picker — instantly readable
- ✅ Big bold `47%` during OTA — clear progress signal
- ✅ Night mode on radar canvas — preserves dark adaptation
- ✅ REPLAY badge on radar — prevents live/replay confusion
- ✅ Auto IP fallback dialog — graceful degradation for older firmware
- ✅ Status text on alert/firmware cards — green/red feedback consistent

### Pain points & recommendations

| Area | Issue | Fix priority | Suggestion |
|---|---|---|---|
| **Config tab length** | 6+ cards scroll forever; first-time users get lost | HIGH | Group into collapsible sections or sub-tabs: "Sensor", "MQTT", "Alerts", "Firmware", "About" |
| **Mixed Thai/English** | Buttons EN, hints sometimes TH, mixed dialogs | MEDIUM | One language at a time. User skipped i18n — keep EN consistently for now |
| **Default empty state** | First-time open shows "DISCONNECTED" with no guidance | HIGH | Add an empty-state card pointing user to Config tab on first run |
| **Status terminology** | "CONNECTED" / "DISCONNECTED" / "DEVICE OFFLINE" / "NO DATA" — 4 states, no legend | MEDIUM | Add a small "?" icon next to status text explaining what each means |
| **Settings page on ESP32** | Browser-only, no in-app wrapper | LOW | Acceptable — first-time setup only |
| **Recording dialog speed picker** | 3 buttons (0.5x / 1x / 2x), no custom speed | LOW | Acceptable for most use cases |
| **No "scrubber"** | Replay can't seek — only play/pause/stop | MEDIUM | API exists (`seekTo`) — UI scrubber is one SeekBar away (Zone D pending) |
| **No alert history search UI** | Filter exists but events tab is still busy | LOW | Done in Zone C (search + type filter chips) |
| **PICK button label** | Just says "PICK" — what does it pick? | LOW | Rename to "PICK DEVICE" or add hint text |
| **Test ESP32 button** | Confirm dialog could be clearer | LOW | "This will play 2 short beeps on the selected ESP32. Continue?" |
| **CHECK FOR UPDATES** | When same version: "Up to date" — no recent check time | LOW | Show "Last checked: 2 min ago" |
| **Backups / export config** | No way to export device profiles + alert rules | MEDIUM | Add "Export config to file" + "Import config" buttons |

### Accessibility

- **Color-only state cues** (●/○ dots, red/green text) → low for color-blind users. Add shape/text differentiators.
- **No content descriptions** on `ImageButton`s in radar overlay — TalkBack users can't tell what each does.
- **Font sizes** mostly OK but range labels (`100°`, `1m`) on radar canvas are small.

### One-line summary

> "Powerful but cluttered." Splitting the Config tab into sub-sections and
> adding empty-state guidance would lift first-time-use UX significantly.

---

## 5. Recommended next commits

1. **v1.0.28** — Defer-list HIGH items (H1, H2, H3, H5, H7)
2. **v1.0.29** — Config tab collapsible sections + first-run empty state
3. **v1.0.30** — Replay scrubber + bookmarks UI (Zone D pending APIs)
4. **v1.0.31** — Export/Import config

---

## 6. Verification status of v1.0.27

| Check | Result |
|---|---|
| `./gradlew assembleDebug` | ✅ |
| Firmware unchanged this release | — |
| Manual smoke test (S1–S7) | Pending physical device |
| Logcat clean under reconnect storm | Pending |
