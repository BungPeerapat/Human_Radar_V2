package com.example.radarhumanapplication;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.gson.JsonObject;

public class LogsFragment extends Fragment implements MqttService.LogListener {

    private RecyclerView rvLogs;
    private LogAdapter adapter;
    private MaterialSwitch swAutoScroll;
    private TextView tvLogCount;
    private MqttService mqtt;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        mqtt = MqttService.getInstance();

        rvLogs = v.findViewById(R.id.rv_logs);
        swAutoScroll = v.findViewById(R.id.sw_auto_scroll);
        tvLogCount = v.findViewById(R.id.tv_log_count);
        MaterialButton btnFetch = v.findViewById(R.id.btn_fetch_logs);
        MaterialButton btnClear = v.findViewById(R.id.btn_clear_logs);

        adapter = new LogAdapter(requireContext());
        rvLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvLogs.setAdapter(adapter);

        // Load existing buffer
        adapter.setEntries(mqtt.getLogBuffer());
        updateCount();

        btnFetch.setOnClickListener(x -> {
            if (mqtt.isConnected()) {
                JsonObject extras = new JsonObject();
                extras.addProperty("limit", 100);
                mqtt.sendCommand("get_log_buffer", extras);
            }
        });

        btnClear.setOnClickListener(x -> {
            adapter.clear();
            mqtt.clearLogBuffer();
            updateCount();
        });

        mqtt.addLogListener(this);
    }

    @Override
    public void onDestroyView() {
        mqtt.removeLogListener(this);
        super.onDestroyView();
    }

    @Override
    public void onLogReceived(MqttService.LogEntry entry) {
        if (!isAdded()) return;
        adapter.addEntry(entry);
        updateCount();
        if (swAutoScroll.isChecked() && adapter.getItemCount() > 0) {
            rvLogs.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private void updateCount() {
        tvLogCount.setText(adapter.getItemCount() + " entries");
    }
}
