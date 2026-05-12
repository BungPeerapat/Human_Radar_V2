package com.example.radarhumanapplication.profiles;

import android.content.Context;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight helper that surfaces a list of saved {@link ConnectionProfile}s as a
 * single-choice picker dialog. Used by any feature that needs to ask "which device?"
 * before executing an action (e.g. TEST ESP32 buzzer, push alert config to specific
 * device, configure online/offline notification per device).
 *
 * <p>If only one profile exists the dialog is skipped and the callback fires
 * immediately with that profile — keeps the single-device workflow frictionless
 * while supporting multi-device installs.</p>
 */
public final class DevicePickerDialog {

    public interface Listener {
        void onDevicePicked(ConnectionProfile profile);
    }

    private DevicePickerDialog() {}

    public static void show(Context ctx, String title, Listener listener) {
        if (ctx == null || listener == null) return;
        List<ConnectionProfile> profiles = ProfileManager.getInstance().getProfiles();
        if (profiles.isEmpty()) {
            new AlertDialog.Builder(ctx)
                    .setTitle("No devices saved")
                    .setMessage("Add a connection profile first.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        if (profiles.size() == 1) {
            listener.onDevicePicked(profiles.get(0));
            return;
        }
        final List<ConnectionProfile> snapshot = new ArrayList<>(profiles);
        String[] labels = new String[snapshot.size()];
        for (int i = 0; i < snapshot.size(); i++) {
            ConnectionProfile p = snapshot.get(i);
            String name = (p.name == null || p.name.isEmpty()) ? "(unnamed)" : p.name;
            String dev = (p.deviceName == null || p.deviceName.isEmpty()) ? "?" : p.deviceName;
            String ip  = TextUtils.isEmpty(p.espHttpIp) ? "no IP" : p.espHttpIp;
            labels[i] = name + " — " + dev + " · " + ip;
        }
        // Default to currently active profile if known
        String activeId = ProfileManager.getInstance().getActiveId();
        int defaultIdx = 0;
        if (activeId != null) {
            for (int i = 0; i < snapshot.size(); i++) {
                if (activeId.equals(snapshot.get(i).id)) { defaultIdx = i; break; }
            }
        }
        final int[] chosen = { defaultIdx };
        new AlertDialog.Builder(ctx)
                .setTitle(title == null ? "Pick a device" : title)
                .setSingleChoiceItems(labels, defaultIdx, (d, which) -> chosen[0] = which)
                .setPositiveButton("OK", (d, w) -> listener.onDevicePicked(snapshot.get(chosen[0])))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
