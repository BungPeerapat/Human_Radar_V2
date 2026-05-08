package com.example.radarhumanapplication;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.radarhumanapplication.BuildConfig;
import com.example.radarhumanapplication.update.UpdateDialog;
import com.example.radarhumanapplication.update.UpdateManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ConfigFragment extends Fragment implements MqttService.ConfigAckListener {

    private TextInputEditText cfgDeviceName, cfgPublishInterval, cfgUnmannedDelay, cfgTargetTimeout;
    private MaterialSwitch cfgMultiTarget;
    private Slider cfgSensitivity;
    private MaterialButton btnSendConfig, btnCheckUpdate;
    private TextView tvConfigAck, tvAppVersion;
    private MqttService mqtt;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        mqtt = MqttService.getInstance();

        cfgDeviceName = v.findViewById(R.id.cfg_device_name);
        cfgPublishInterval = v.findViewById(R.id.cfg_publish_interval);
        cfgUnmannedDelay = v.findViewById(R.id.cfg_unmanned_delay);
        cfgTargetTimeout = v.findViewById(R.id.cfg_target_timeout);
        cfgMultiTarget = v.findViewById(R.id.cfg_multi_target);
        cfgSensitivity = v.findViewById(R.id.cfg_sensitivity);
        btnSendConfig = v.findViewById(R.id.btn_send_config);
        tvConfigAck = v.findViewById(R.id.tv_config_ack);
        btnCheckUpdate = v.findViewById(R.id.btn_check_update);
        tvAppVersion = v.findViewById(R.id.tv_app_version);

        cfgDeviceName.setText(mqtt.getDeviceName());
        tvAppVersion.setText("Current: v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");

        btnSendConfig.setOnClickListener(this::onSendConfig);
        btnCheckUpdate.setOnClickListener(this::onCheckUpdate);
        mqtt.addConfigAckListener(this);
    }

    private void onCheckUpdate(View v) {
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("Checking…");
        UpdateManager.getInstance().checkManual(requireContext(), result -> {
            if (!isAdded()) return;
            btnCheckUpdate.setEnabled(true);
            btnCheckUpdate.setText("CHECK FOR UPDATES");
            switch (result.status) {
                case UPDATE_AVAILABLE:
                    UpdateManager.getInstance().clearSkip(requireContext());
                    UpdateDialog.show(getParentFragmentManager(), result.info);
                    break;
                case UP_TO_DATE:
                    Toast.makeText(requireContext(), "You are on the latest version", Toast.LENGTH_SHORT).show();
                    break;
                case DISABLED:
                    Toast.makeText(requireContext(), "Update channel not configured", Toast.LENGTH_LONG).show();
                    break;
                case ERROR:
                default:
                    Toast.makeText(requireContext(),
                            "Check failed: " + (result.errorMessage != null ? result.errorMessage : "unknown"),
                            Toast.LENGTH_LONG).show();
                    break;
            }
        });
    }

    @Override
    public void onDestroyView() {
        mqtt.removeConfigAckListener(this);
        super.onDestroyView();
    }

    private void onSendConfig(View v) {
        if (!mqtt.isConnected()) {
            Toast.makeText(requireContext(), "Not connected to MQTT", Toast.LENGTH_SHORT).show();
            return;
        }

        JsonObject config = new JsonObject();

        String name = getText(cfgDeviceName);
        if (!name.isEmpty()) config.addProperty("device_name", name);

        int pubInt = parseInt(getText(cfgPublishInterval), -1);
        if (pubInt >= 50 && pubInt <= 2000) config.addProperty("publish_interval_ms", pubInt);

        int unmDly = parseInt(getText(cfgUnmannedDelay), -1);
        if (unmDly >= 1000 && unmDly <= 60000) config.addProperty("unmanned_delay_ms", unmDly);

        int tgtTout = parseInt(getText(cfgTargetTimeout), -1);
        if (tgtTout >= 100 && tgtTout <= 10000) config.addProperty("target_timeout_ms", tgtTout);

        config.addProperty("multi_target_mode", cfgMultiTarget.isChecked());
        config.addProperty("sensitivity", (int) cfgSensitivity.getValue());

        mqtt.sendConfig(config);
        tvConfigAck.setText("Sending...");
        tvConfigAck.setTextColor(requireContext().getColor(R.color.radar_yellow));
    }

    @Override
    public void onConfigAck(JsonObject ack) {
        if (!isAdded()) return;
        String status = ack.has("status") ? ack.get("status").getAsString() : "?";
        Gson gson = new Gson();
        String formatted = gson.toJson(ack);

        int color;
        switch (status) {
            case "ok":      color = R.color.radar_green; break;
            case "partial": color = R.color.radar_yellow; break;
            default:        color = R.color.radar_red; break;
        }

        tvConfigAck.setText(formatted);
        tvConfigAck.setTextColor(requireContext().getColor(color));
    }

    private String getText(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
