package com.example.radarhumanapplication;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class DashboardFragment extends Fragment
        implements MqttService.ConnectionListener,
                   MqttService.TargetListener,
                   MqttService.StatusListener,
                   MqttService.CmdAckListener {

    private TextInputEditText etHost, etPort, etDeviceName, etUsername, etPassword;
    private MaterialButton btnConnect;
    private TextView tvMqttStatus, tvDeviceStatus;
    private TextView tvTarget1, tvTarget2, tvTarget3, tvFrameInfo;
    private MaterialButton btnRestart, btnFactoryReset;

    private MqttService mqtt;
    private static final String PREFS = "radar_prefs";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        mqtt = MqttService.getInstance();

        etHost = v.findViewById(R.id.et_broker_host);
        etPort = v.findViewById(R.id.et_broker_port);
        etDeviceName = v.findViewById(R.id.et_device_name);
        etUsername = v.findViewById(R.id.et_username);
        etPassword = v.findViewById(R.id.et_password);
        btnConnect = v.findViewById(R.id.btn_connect);
        tvMqttStatus = v.findViewById(R.id.tv_mqtt_status);
        tvDeviceStatus = v.findViewById(R.id.tv_device_status);
        tvTarget1 = v.findViewById(R.id.tv_target1);
        tvTarget2 = v.findViewById(R.id.tv_target2);
        tvTarget3 = v.findViewById(R.id.tv_target3);
        tvFrameInfo = v.findViewById(R.id.tv_frame_info);
        btnRestart = v.findViewById(R.id.btn_restart);
        btnFactoryReset = v.findViewById(R.id.btn_factory_reset);

        loadPrefs();
        updateConnectButton();

        btnConnect.setOnClickListener(this::onConnectClick);
        btnRestart.setOnClickListener(x -> {
            if (mqtt.isConnected()) mqtt.sendCommand("restart");
        });
        btnFactoryReset.setOnClickListener(x -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Factory Reset")
                    .setMessage("Reset ESP32 to factory defaults?")
                    .setPositiveButton("Reset", (d, w) -> mqtt.sendCommand("factory_reset"))
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Register listeners
        mqtt.addConnectionListener(this);
        mqtt.addTargetListener(this);
        mqtt.addStatusListener(this);
        mqtt.addCmdAckListener(this);

        // Show existing state
        if (mqtt.isConnected()) {
            tvMqttStatus.setText("connected");
            tvMqttStatus.setTextColor(getColor(R.color.radar_green));
            btnConnect.setText("DISCONNECT");
        }
        tvDeviceStatus.setText(mqtt.getDeviceStatus());
    }

    @Override
    public void onDestroyView() {
        mqtt.removeConnectionListener(this);
        mqtt.removeTargetListener(this);
        mqtt.removeStatusListener(this);
        mqtt.removeCmdAckListener(this);
        super.onDestroyView();
    }

    private void onConnectClick(View v) {
        if (mqtt.isConnected()) {
            mqtt.disconnect();
            updateConnectButton();
            tvMqttStatus.setText("disconnected");
            tvMqttStatus.setTextColor(getColor(R.color.radar_red));
            return;
        }

        String host = getText(etHost);
        int port = parseInt(getText(etPort), 1883);
        String device = getText(etDeviceName);
        String user = getText(etUsername);
        String pass = getText(etPassword);

        if (host.isEmpty()) {
            etHost.setError("Required");
            return;
        }
        if (device.isEmpty()) device = "HumanRadar";

        savePrefs(host, port, device, user, pass);
        mqtt.configure(host, port, device, user, pass);
        mqtt.connect();

        tvMqttStatus.setText("connecting...");
        tvMqttStatus.setTextColor(getColor(R.color.radar_yellow));
        btnConnect.setText("CONNECTING...");
        btnConnect.setEnabled(false);
    }

    // --- MqttService callbacks (called on main thread) ---

    @Override
    public void onConnected() {
        if (!isAdded()) return;
        tvMqttStatus.setText("connected");
        tvMqttStatus.setTextColor(getColor(R.color.radar_green));
        btnConnect.setText("DISCONNECT");
        btnConnect.setEnabled(true);
    }

    @Override
    public void onDisconnected(String reason) {
        if (!isAdded()) return;
        tvMqttStatus.setText("disconnected");
        tvMqttStatus.setTextColor(getColor(R.color.radar_red));
        btnConnect.setText("CONNECT");
        btnConnect.setEnabled(true);
    }

    @Override
    public void onTargetsReceived(JsonObject data) {
        if (!isAdded()) return;
        JsonArray targets = data.getAsJsonArray("t");
        if (targets == null) return;

        TextView[] tvs = {tvTarget1, tvTarget2, tvTarget3};
        for (int i = 0; i < Math.min(3, targets.size()); i++) {
            JsonObject t = targets.get(i).getAsJsonObject();
            boolean present = t.has("p") && t.get("p").getAsBoolean();
            if (present) {
                tvs[i].setText(String.format("T%d: X:%+d Y:%d Spd:%+dcm/s Dist:%dmm",
                        i + 1,
                        t.get("x").getAsInt(),
                        t.get("y").getAsInt(),
                        t.get("s").getAsInt(),
                        t.get("d").getAsInt()));
                tvs[i].setTextColor(getColor(R.color.radar_green));
            } else {
                tvs[i].setText(String.format("T%d: ---", i + 1));
                tvs[i].setTextColor(getColor(R.color.radar_text_dim));
            }
        }

        long fc = data.has("fc") ? data.get("fc").getAsLong() : 0;
        long ec = data.has("ec") ? data.get("ec").getAsLong() : 0;
        tvFrameInfo.setText(String.format("Frames: %d | Errors: %d", fc, ec));
    }

    @Override
    public void onDeviceStatus(String status) {
        if (!isAdded()) return;
        tvDeviceStatus.setText(status);
        tvDeviceStatus.setTextColor("online".equals(status) ?
                getColor(R.color.radar_green) : getColor(R.color.radar_red));
    }

    @Override
    public void onCmdAck(JsonObject ack) {
        if (!isAdded()) return;
        String msg = ack.has("message") ? ack.get("message").getAsString() : "done";
        Toast.makeText(requireContext(), "CMD: " + msg, Toast.LENGTH_SHORT).show();
    }

    // --- Helpers ---

    private void updateConnectButton() {
        btnConnect.setText(mqtt.isConnected() ? "DISCONNECT" : "CONNECT");
        btnConnect.setEnabled(true);
    }

    private String getText(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private int getColor(int resId) {
        return requireContext().getColor(resId);
    }

    private void savePrefs(String host, int port, String device, String user, String pass) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("device", device)
                .putString("user", user)
                .putString("pass", pass)
                .apply();
    }

    private void loadPrefs() {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        etHost.setText(prefs.getString("host", ""));
        etPort.setText(String.valueOf(prefs.getInt("port", 1883)));
        etDeviceName.setText(prefs.getString("device", "HumanRadar"));
        etUsername.setText(prefs.getString("user", ""));
        etPassword.setText(prefs.getString("pass", ""));
    }
}
