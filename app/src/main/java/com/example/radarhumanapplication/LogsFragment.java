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

import com.example.radarhumanapplication.eventlog.Event;
import com.example.radarhumanapplication.eventlog.EventAdapter;
import com.example.radarhumanapplication.eventlog.EventLogger;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.gson.JsonObject;

public class LogsFragment extends Fragment
        implements MqttService.LogListener, EventLogger.EventListener {

    private RecyclerView rvLogs;
    private RecyclerView rvEvents;
    private LogAdapter adapter;
    private EventAdapter eventAdapter;
    private MaterialSwitch swAutoScroll;
    private TextView tvLogCount;
    private MqttService mqtt;
    private EventLogger eventLogger;

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
        eventLogger = EventLogger.getInstance();
        eventLogger.attach(requireContext());

        rvLogs = v.findViewById(R.id.rv_logs);
        rvEvents = v.findViewById(R.id.rv_events);
        swAutoScroll = v.findViewById(R.id.sw_auto_scroll);
        tvLogCount = v.findViewById(R.id.tv_log_count);
        MaterialButton btnFetch = v.findViewById(R.id.btn_fetch_logs);
        MaterialButton btnClear = v.findViewById(R.id.btn_clear_logs);
        MaterialButtonToggleGroup tgMode = v.findViewById(R.id.tg_log_mode);
        MaterialButton btnTabEsp = v.findViewById(R.id.btn_tab_esp_logs);
        MaterialButton btnTabEvents = v.findViewById(R.id.btn_tab_events);

        adapter = new LogAdapter(requireContext());
        rvLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvLogs.setAdapter(adapter);

        eventAdapter = new EventAdapter(requireContext());
        rvEvents.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvEvents.setAdapter(eventAdapter);

        // Load existing buffers
        adapter.setEntries(mqtt.getLogBuffer());
        eventAdapter.setEntries(eventLogger.getEvents());
        updateCount();

        tgMode.check(R.id.btn_tab_esp_logs);
        tgMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_tab_esp_logs) {
                showEspLogs();
            } else if (checkedId == R.id.btn_tab_events) {
                showEvents();
            }
        });

        btnFetch.setOnClickListener(x -> {
            if (mqtt.isConnected()) {
                JsonObject extras = new JsonObject();
                extras.addProperty("limit", 100);
                mqtt.sendCommand("get_log_buffer", extras);
            }
        });

        btnClear.setOnClickListener(x -> {
            // Clear whichever list is currently visible
            if (rvEvents.getVisibility() == View.VISIBLE) {
                eventAdapter.clear();
                eventLogger.clear();
            } else {
                adapter.clear();
                mqtt.clearLogBuffer();
            }
            updateCount();
        });

        mqtt.addLogListener(this);
        eventLogger.addEventListener(this);
    }

    private void showEspLogs() {
        rvLogs.setVisibility(View.VISIBLE);
        rvEvents.setVisibility(View.GONE);
        updateCount();
    }

    private void showEvents() {
        rvLogs.setVisibility(View.GONE);
        rvEvents.setVisibility(View.VISIBLE);
        updateCount();
    }

    @Override
    public void onDestroyView() {
        mqtt.removeLogListener(this);
        eventLogger.removeEventListener(this);
        super.onDestroyView();
    }

    @Override
    public void onLogReceived(MqttService.LogEntry entry) {
        if (!isAdded()) return;
        adapter.addEntry(entry);
        updateCount();
        if (rvLogs.getVisibility() == View.VISIBLE
                && swAutoScroll.isChecked() && adapter.getItemCount() > 0) {
            rvLogs.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    @Override
    public void onEvent(Event e) {
        if (!isAdded()) return;
        eventAdapter.addEntry(e);
        updateCount();
        if (rvEvents.getVisibility() == View.VISIBLE
                && swAutoScroll.isChecked() && eventAdapter.getItemCount() > 0) {
            rvEvents.scrollToPosition(eventAdapter.getItemCount() - 1);
        }
    }

    private void updateCount() {
        if (rvEvents.getVisibility() == View.VISIBLE) {
            tvLogCount.setText(eventAdapter.getItemCount() + " events");
        } else {
            tvLogCount.setText(adapter.getItemCount() + " entries");
        }
    }
}
