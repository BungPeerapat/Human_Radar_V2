package com.example.radarhumanapplication.sensor;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.example.radarhumanapplication.MqttService;
import com.example.radarhumanapplication.R;
import com.example.radarhumanapplication.alerts.AlertHttpClient;
import com.example.radarhumanapplication.profiles.DevicePickerDialog;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Editor dialog for the firmware's three detection zones (z0/z1/z2).
 *
 * <p>The user picks a target ESP32 via {@link DevicePickerDialog} when FETCH
 * or PUSH is tapped, then the dialog talks to {@code /api/config} through
 * {@link AlertHttpClient}. The preview canvas updates live as the user types
 * so the rectangle they are configuring is always visible.
 *
 * <p>JSON keys mirror the firmware's {@code handleGetConfig()} output which
 * nests each zone as an object: {@code "z0":{"en":1,"x1":-3000,"y1":0,
 * "x2":3000,"y2":6000}} (see {@code firmware/src/web_server.cpp}). The
 * outgoing POST follows the same nested shape.
 */
public class DetectionZonesDialog extends DialogFragment {

    public static final String TAG = "DetectionZonesDialog";

    private static final int ZONE_COUNT = 3;
    private static final String LOG_TAG = "DetZones";

    public static void show(@NonNull FragmentManager fm) {
        new DetectionZonesDialog().show(fm, TAG);
    }

    private ZonesPreviewView preview;
    private TextView status;
    private Button btnFetch, btnPush, btnClose;

    private final MaterialSwitch[]      enabledSwitches = new MaterialSwitch[ZONE_COUNT];
    private final TextInputEditText[][] coords          = new TextInputEditText[ZONE_COUNT][4];
    // coords[zone][0..3] = x1, y1, x2, y2

    private final AlertHttpClient http = new AlertHttpClient();

    /** Most recent IP used for FETCH/PUSH (so PUSH can reuse it without re-picking). */
    @Nullable private String lastIp;

    // ───────────────────────── Lifecycle ──────────────────────────────────────

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        View view = LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_detection_zones, null);

        bindViews(view);
        wireListeners();
        rebuildPreview();

        return new AlertDialog.Builder(ctx)
                .setTitle("Detection zones")
                .setView(view)
                .create();
    }

    @Override
    public void onDestroy() {
        http.shutdown();
        super.onDestroy();
    }

    // ───────────────────────── View binding ───────────────────────────────────

    private void bindViews(View v) {
        preview = v.findViewById(R.id.zones_preview);
        status  = v.findViewById(R.id.zones_status);
        btnFetch = v.findViewById(R.id.zones_btn_fetch);
        btnPush  = v.findViewById(R.id.zones_btn_push);
        btnClose = v.findViewById(R.id.zones_btn_close);

        enabledSwitches[0] = v.findViewById(R.id.zone0_enabled);
        enabledSwitches[1] = v.findViewById(R.id.zone1_enabled);
        enabledSwitches[2] = v.findViewById(R.id.zone2_enabled);

        coords[0][0] = v.findViewById(R.id.zone0_x1);
        coords[0][1] = v.findViewById(R.id.zone0_y1);
        coords[0][2] = v.findViewById(R.id.zone0_x2);
        coords[0][3] = v.findViewById(R.id.zone0_y2);

        coords[1][0] = v.findViewById(R.id.zone1_x1);
        coords[1][1] = v.findViewById(R.id.zone1_y1);
        coords[1][2] = v.findViewById(R.id.zone1_x2);
        coords[1][3] = v.findViewById(R.id.zone1_y2);

        coords[2][0] = v.findViewById(R.id.zone2_x1);
        coords[2][1] = v.findViewById(R.id.zone2_y1);
        coords[2][2] = v.findViewById(R.id.zone2_x2);
        coords[2][3] = v.findViewById(R.id.zone2_y2);
    }

    private void wireListeners() {
        TextWatcher previewWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { rebuildPreview(); }
        };
        CompoundButton.OnCheckedChangeListener switchListener =
                (buttonView, isChecked) -> rebuildPreview();

        for (int i = 0; i < ZONE_COUNT; i++) {
            enabledSwitches[i].setOnCheckedChangeListener(switchListener);
            for (int f = 0; f < 4; f++) {
                coords[i][f].addTextChangedListener(previewWatcher);
            }
        }

        btnFetch.setOnClickListener(v -> onFetch());
        btnPush.setOnClickListener(v -> onPush());
        btnClose.setOnClickListener(v -> dismiss());
    }

    // ───────────────────────── Preview ────────────────────────────────────────

    private void rebuildPreview() {
        List<ZonesPreviewView.Zone> list = new ArrayList<>(ZONE_COUNT);
        for (int i = 0; i < ZONE_COUNT; i++) {
            list.add(new ZonesPreviewView.Zone(
                    enabledSwitches[i].isChecked(),
                    parseInt(coords[i][0]),
                    parseInt(coords[i][1]),
                    parseInt(coords[i][2]),
                    parseInt(coords[i][3])));
        }
        if (preview != null) preview.setZones(list);
    }

    private static int parseInt(@Nullable TextInputEditText et) {
        if (et == null || et.getText() == null) return 0;
        String s = et.getText().toString().trim();
        if (s.isEmpty() || s.equals("-")) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // ───────────────────────── FETCH flow ─────────────────────────────────────

    private void onFetch() {
        if (lastIp != null && !lastIp.isEmpty()) {
            doFetch(lastIp);
            return;
        }
        DevicePickerDialog.show(requireContext(), "Fetch zones from?", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                setStatus("No IP for " + safeName(p.name) + " — try Connection Profiles first.", true);
                return;
            }
            lastIp = p.espHttpIp;
            doFetch(p.espHttpIp);
        });
    }

    private void doFetch(@NonNull String ip) {
        setStatus("Fetching from " + ip + "…", false);
        http.fetchDeviceConfig(ip, (json, err) -> {
            if (!isAdded()) return;
            if (err != null || json == null) {
                setStatus("Fetch failed: " + err, true);
                return;
            }
            applyFromJson(json);
            setStatus("Fetched OK from " + ip, false);
        });
    }

    /**
     * Populate switches + fields from the firmware's GET /api/config payload.
     * The firmware emits zones as nested objects:
     *   "z0":{"en":1,"x1":-3000,"y1":0,"x2":3000,"y2":6000}
     */
    private void applyFromJson(@NonNull JsonObject root) {
        for (int i = 0; i < ZONE_COUNT; i++) {
            String key = "z" + i;
            JsonObject zone = optObject(root, key);
            if (zone == null) continue;
            enabledSwitches[i].setChecked(optInt(zone, "en", 0) != 0);
            coords[i][0].setText(String.valueOf(optInt(zone, "x1", 0)));
            coords[i][1].setText(String.valueOf(optInt(zone, "y1", 0)));
            coords[i][2].setText(String.valueOf(optInt(zone, "x2", 0)));
            coords[i][3].setText(String.valueOf(optInt(zone, "y2", 0)));
        }
        rebuildPreview();
    }

    // ───────────────────────── PUSH flow ──────────────────────────────────────

    private void onPush() {
        if (lastIp != null && !lastIp.isEmpty()) {
            doPush(lastIp);
            return;
        }
        DevicePickerDialog.show(requireContext(), "Push zones to?", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                setStatus("No IP for " + safeName(p.name) + " — pick a different device.", true);
                return;
            }
            lastIp = p.espHttpIp;
            doPush(p.espHttpIp);
        });
    }

    private void doPush(@NonNull String ip) {
        JsonObject body = buildPushBody();
        setStatus("Pushing to " + ip + "…", false);
        http.postRawConfig(ip, body, (ok, err) -> {
            if (!isAdded()) return;
            if (Boolean.TRUE.equals(ok)) {
                setStatus("Pushed OK to " + ip, false);
            } else {
                setStatus("Push failed: " + err, true);
            }
        });
    }

    /**
     * Build the POST body. Sends both the firmware's native nested shape
     * ({@code "z0":{...}}) and a flat key shape ({@code "z0_en"}, ...) so
     * either save-side parser layout works without coupling the dialog
     * tightly to one firmware revision.
     */
    private JsonObject buildPushBody() {
        JsonObject body = new JsonObject();
        for (int i = 0; i < ZONE_COUNT; i++) {
            int en = enabledSwitches[i].isChecked() ? 1 : 0;
            int x1 = parseInt(coords[i][0]);
            int y1 = parseInt(coords[i][1]);
            int x2 = parseInt(coords[i][2]);
            int y2 = parseInt(coords[i][3]);

            JsonObject zone = new JsonObject();
            zone.addProperty("en", en);
            zone.addProperty("x1", x1);
            zone.addProperty("y1", y1);
            zone.addProperty("x2", x2);
            zone.addProperty("y2", y2);
            body.add("z" + i, zone);

            String prefix = "z" + i + "_";
            body.addProperty(prefix + "en", en);
            body.addProperty(prefix + "x1", x1);
            body.addProperty(prefix + "y1", y1);
            body.addProperty(prefix + "x2", x2);
            body.addProperty(prefix + "y2", y2);
        }
        return body;
    }

    // ───────────────────────── Status / helpers ───────────────────────────────

    private void setStatus(String msg, boolean isError) {
        if (status == null) return;
        status.setText(msg);
        Context ctx = getContext();
        if (ctx != null) {
            status.setTextColor(ctx.getColor(
                    isError ? R.color.radar_red : R.color.radar_text_dim));
        }
        if (isError) Log.w(LOG_TAG, msg);
    }

    private static String safeName(@Nullable String name) {
        return TextUtils.isEmpty(name) ? "device" : name;
    }

    @Nullable
    private static JsonObject optObject(JsonObject src, String key) {
        if (src == null || !src.has(key)) return null;
        JsonElement el = src.get(key);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static int optInt(JsonObject src, String key, int dflt) {
        if (src == null || !src.has(key)) return dflt;
        JsonElement el = src.get(key);
        if (el == null || el.isJsonNull()) return dflt;
        try { return el.getAsInt(); } catch (Exception e) { return dflt; }
    }

    /** Suppress unused-import warnings for MqttService — reserved for future
     * "default to the active device" shortcut without re-prompting the picker. */
    @SuppressWarnings("unused")
    private String activeDeviceNameHint() {
        MqttService mqtt = MqttService.getInstance();
        return mqtt == null ? null : mqtt.getDeviceName();
    }
}
