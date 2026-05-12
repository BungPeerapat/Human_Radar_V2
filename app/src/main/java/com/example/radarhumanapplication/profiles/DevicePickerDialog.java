package com.example.radarhumanapplication.profiles;

import android.content.Context;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;

import com.example.radarhumanapplication.MqttService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Pick a device" dialog backed by the union of:
 *   1. Devices currently publishing on MQTT (humanradar/+/status, online first)
 *   2. Saved {@link ConnectionProfile}s (so an offline device still appears)
 *
 * Each row displays a status dot and a freshness hint so the user can tell at a glance
 * which ESP32 to send the command to. If only one candidate exists the dialog is
 * skipped and the callback fires immediately.
 */
public final class DevicePickerDialog {

    public interface Listener {
        void onDevicePicked(ConnectionProfile profile);
    }

    private DevicePickerDialog() {}

    public static void show(Context ctx, String title, Listener listener) {
        if (ctx == null || listener == null) return;

        // ── Gather candidates ─────────────────────────────────────────────────
        // Index profiles by deviceName so MQTT-discovered devices can hydrate IP.
        Map<String, ConnectionProfile> byName = new HashMap<>();
        for (ConnectionProfile p : ProfileManager.getInstance().getProfiles()) {
            if (p.deviceName != null && !p.deviceName.isEmpty()) {
                byName.put(p.deviceName, p);
            }
        }

        // Use LinkedHashMap so we preserve insertion order: online > offline (MQTT) > profile-only
        LinkedHashMap<String, Row> rows = new LinkedHashMap<>();

        // 1) MQTT-online devices first
        for (MqttService.DiscoveredDevice d :
                MqttService.getInstance().getDiscoveredDevices()) {
            rows.put(d.deviceName, Row.fromDiscovery(d, byName.get(d.deviceName)));
        }
        // 2) Saved profiles that haven't been seen via MQTT
        for (ConnectionProfile p : ProfileManager.getInstance().getProfiles()) {
            String dn = p.deviceName == null ? "" : p.deviceName;
            if (!rows.containsKey(dn)) {
                rows.put(dn.isEmpty() ? p.id : dn, Row.fromProfile(p));
            }
        }

        if (rows.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("No devices found")
                    .setMessage("Connect to MQTT or add a Connection Profile first.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        // ── Single candidate shortcut ──────────────────────────────────────────
        if (rows.size() == 1) {
            Row only = rows.values().iterator().next();
            if (only.profile != null) {
                if (TextUtils.isEmpty(only.profile.espHttpIp)) {
                    MqttService.DiscoveredDevice live = findDiscovered(only.deviceName);
                    if (live != null && live.ip != null && !live.ip.isEmpty()) {
                        ConnectionProfile hydrated = new ConnectionProfile(
                                only.profile.id, only.profile.name, only.profile.brokerHost,
                                only.profile.brokerPort, only.profile.deviceName,
                                only.profile.username, only.profile.password);
                        hydrated.espHttpIp = live.ip;
                        listener.onDevicePicked(hydrated);
                        return;
                    }
                }
                listener.onDevicePicked(only.profile);
                return;
            }
            // MQTT-only device with no saved profile: synthesize a transient profile.
            ConnectionProfile transientProfile = synthesizeProfile(only.deviceName);
            listener.onDevicePicked(transientProfile);
            return;
        }

        // ── Build the dialog ───────────────────────────────────────────────────
        final List<Row> rowList = new ArrayList<>(rows.values());
        String[] labels = new String[rowList.size()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < rowList.size(); i++) {
            labels[i] = rowList.get(i).label(now);
        }

        // Default-select the active MQTT device if present; otherwise first online; otherwise 0.
        int defaultIdx = 0;
        String activeName = MqttService.getInstance().getDeviceName();
        for (int i = 0; i < rowList.size(); i++) {
            if (activeName != null && activeName.equals(rowList.get(i).deviceName)) {
                defaultIdx = i;
                break;
            }
        }
        if (defaultIdx == 0) {
            for (int i = 0; i < rowList.size(); i++) {
                if (rowList.get(i).onlineState == OnlineState.ONLINE) { defaultIdx = i; break; }
            }
        }

        final int[] chosen = { defaultIdx };
        new AlertDialog.Builder(ctx)
                .setTitle(title == null ? "Pick a device" : title)
                .setSingleChoiceItems(labels, defaultIdx, (d, which) -> chosen[0] = which)
                .setPositiveButton("OK", (d, w) -> {
                    Row r = rowList.get(chosen[0]);
                    if (r.profile != null) {
                        // If the saved profile has no IP but MQTT discovery does,
                        // hand the caller a hydrated copy so they don't need to ask.
                        if (TextUtils.isEmpty(r.profile.espHttpIp)) {
                            MqttService.DiscoveredDevice live = findDiscovered(r.deviceName);
                            if (live != null && live.ip != null && !live.ip.isEmpty()) {
                                ConnectionProfile hydrated = new ConnectionProfile(
                                        r.profile.id, r.profile.name, r.profile.brokerHost,
                                        r.profile.brokerPort, r.profile.deviceName,
                                        r.profile.username, r.profile.password);
                                hydrated.espHttpIp = live.ip;
                                listener.onDevicePicked(hydrated);
                                return;
                            }
                        }
                        listener.onDevicePicked(r.profile);
                    } else {
                        listener.onDevicePicked(synthesizeProfile(r.deviceName));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static ConnectionProfile synthesizeProfile(String deviceName) {
        ConnectionProfile p = new ConnectionProfile();
        p.id = "transient-" + deviceName;
        p.name = deviceName;
        p.deviceName = deviceName;
        // Hydrate IP from MQTT discovery if the device published its /info topic.
        MqttService.DiscoveredDevice d = null;
        for (MqttService.DiscoveredDevice candidate :
                MqttService.getInstance().getDiscoveredDevices()) {
            if (deviceName != null && deviceName.equals(candidate.deviceName)) {
                d = candidate;
                break;
            }
        }
        p.espHttpIp = (d != null && d.ip != null) ? d.ip : "";
        return p;
    }

    /** Look up the live MQTT discovery record for a deviceName (or null). */
    private static MqttService.DiscoveredDevice findDiscovered(String deviceName) {
        if (deviceName == null) return null;
        for (MqttService.DiscoveredDevice d :
                MqttService.getInstance().getDiscoveredDevices()) {
            if (deviceName.equals(d.deviceName)) return d;
        }
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────────
    //  Row model
    // ────────────────────────────────────────────────────────────────────────────
    private enum OnlineState { ONLINE, OFFLINE, UNKNOWN }

    private static class Row {
        final String deviceName;
        final ConnectionProfile profile; // may be null when MQTT-only
        final OnlineState onlineState;
        final long lastSeenMs;            // 0 if unknown

        Row(String deviceName, ConnectionProfile profile, OnlineState s, long lastSeen) {
            this.deviceName  = deviceName;
            this.profile     = profile;
            this.onlineState = s;
            this.lastSeenMs  = lastSeen;
        }

        static Row fromDiscovery(MqttService.DiscoveredDevice d, ConnectionProfile prof) {
            OnlineState s = d.isOnline() ? OnlineState.ONLINE : OnlineState.OFFLINE;
            return new Row(d.deviceName, prof, s, d.lastSeenMs);
        }

        static Row fromProfile(ConnectionProfile p) {
            String name = p.deviceName == null || p.deviceName.isEmpty()
                    ? (p.name == null ? "(unnamed)" : p.name)
                    : p.deviceName;
            return new Row(name, p, OnlineState.UNKNOWN, 0L);
        }

        String label(long nowMs) {
            String dot;
            String trailing;
            switch (onlineState) {
                case ONLINE:
                    dot = "● ";
                    trailing = "  ·  ONLINE";
                    break;
                case OFFLINE:
                    dot = "○ ";
                    long sec = (nowMs - lastSeenMs) / 1000L;
                    trailing = "  ·  offline (" + formatAgo(sec) + ")";
                    break;
                default:
                    dot = "  ";
                    trailing = "  ·  unknown";
                    break;
            }
            // Prefer the profile's saved IP, fall back to live MQTT discovery so a
            // device the user just plugged in shows its address without manual entry.
            String ip = (profile != null && !TextUtils.isEmpty(profile.espHttpIp))
                    ? profile.espHttpIp
                    : "";
            if (ip.isEmpty()) {
                MqttService.DiscoveredDevice live = findDiscovered(deviceName);
                if (live != null && live.ip != null && !live.ip.isEmpty()) ip = live.ip;
            }
            String pname = (profile != null && profile.name != null && !profile.name.isEmpty())
                    ? profile.name : "";

            StringBuilder profPart = new StringBuilder();
            if (!pname.isEmpty()) profPart.append("    ").append(pname);
            if (!ip.isEmpty()) {
                if (profPart.length() == 0) profPart.append("    ");
                else profPart.append(" · ");
                profPart.append(ip);
            }
            return dot + deviceName + trailing + profPart;
        }

        private static String formatAgo(long sec) {
            if (sec < 60)    return sec + "s ago";
            if (sec < 3600)  return (sec / 60) + "m ago";
            if (sec < 86400) return (sec / 3600) + "h ago";
            return String.format(Locale.US, "%dd ago", sec / 86400);
        }
    }
}
