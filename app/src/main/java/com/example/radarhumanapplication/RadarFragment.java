package com.example.radarhumanapplication;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.gson.JsonObject;

public class RadarFragment extends Fragment
        implements MqttService.TargetListener, MqttService.ConnectionListener {

    private RadarView radarView;
    private TextView tvTarget1, tvTarget2, tvTarget3;
    private TextView tvStats, tvRadarStatus;
    private MqttService mqtt;

    private static final int[] TARGET_COLORS = {
            0xFFFF4444, 0xFF44FF44, 0xFF4488FF
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_radar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        mqtt = MqttService.getInstance();

        radarView = v.findViewById(R.id.radar_view);
        tvTarget1 = v.findViewById(R.id.tv_radar_t1);
        tvTarget2 = v.findViewById(R.id.tv_radar_t2);
        tvTarget3 = v.findViewById(R.id.tv_radar_t3);
        tvStats = v.findViewById(R.id.tv_radar_stats);
        tvRadarStatus = v.findViewById(R.id.tv_radar_status);

        radarView.setInfoListener((targets, frameCount, errorCount, fps) -> {
            if (!isAdded()) return;
            updateTargetInfo(targets);
            tvStats.setText(String.format("Frames: %d | Errors: %d | FPS: %d",
                    frameCount, errorCount, fps));
        });

        updateConnectionStatus();
        mqtt.addTargetListener(this);
        mqtt.addConnectionListener(this);
    }

    @Override
    public void onDestroyView() {
        mqtt.removeTargetListener(this);
        mqtt.removeConnectionListener(this);
        super.onDestroyView();
    }

    @Override
    public void onTargetsReceived(JsonObject data) {
        if (!isAdded()) return;
        radarView.updateTargets(data);
    }

    @Override
    public void onConnected() {
        if (!isAdded()) return;
        updateConnectionStatus();
    }

    @Override
    public void onDisconnected(String reason) {
        if (!isAdded()) return;
        updateConnectionStatus();
    }

    private void updateConnectionStatus() {
        if (mqtt.isConnected()) {
            tvRadarStatus.setText("CONNECTED");
            tvRadarStatus.setTextColor(getColor(R.color.radar_green));
        } else {
            tvRadarStatus.setText("DISCONNECTED");
            tvRadarStatus.setTextColor(getColor(R.color.radar_red));
        }
    }

    private void updateTargetInfo(RadarView.TargetData[] targets) {
        TextView[] tvs = {tvTarget1, tvTarget2, tvTarget3};
        for (int i = 0; i < 3; i++) {
            RadarView.TargetData t = targets[i];
            if (t.present) {
                String dir = t.speed < 0 ? "\u2191" : "\u2193";
                tvs[i].setText(String.format("T%d  X:%+5d  Y:%5d  D:%5dmm  %s%dcm/s",
                        i + 1, t.x, t.y, t.distance, dir, Math.abs(t.speed)));
                tvs[i].setTextColor(TARGET_COLORS[i]);
            } else {
                tvs[i].setText(String.format("T%d  ---", i + 1));
                tvs[i].setTextColor(getColor(R.color.radar_text_dim));
            }
        }
    }

    private int getColor(int resId) {
        return requireContext().getColor(resId);
    }
}
