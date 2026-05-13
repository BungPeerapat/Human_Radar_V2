package com.example.radarhumanapplication;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
    private View eventFilterBar;
    private EditText etEventSearch;
    private View logFilterBar;
    private com.google.android.material.textfield.MaterialAutoCompleteTextView acLogDevice;
    private android.widget.ArrayAdapter<String> logDeviceAdapter;
    private MqttService.DiscoveryListener logDiscoveryListener;
    /** Sentinel label that maps back to "" (no filter). */
    private static final String LOG_DEVICE_ALL = "(all devices)";

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
        eventFilterBar = v.findViewById(R.id.event_filter_bar);
        etEventSearch  = v.findViewById(R.id.et_event_search);
        logFilterBar   = v.findViewById(R.id.log_filter_bar);
        acLogDevice    = v.findViewById(R.id.ac_log_device);
        MaterialButtonToggleGroup tgEventType = v.findViewById(R.id.tg_event_type);
        MaterialButton btnEventAll   = v.findViewById(R.id.btn_event_all);
        MaterialButton btnEventEnter = v.findViewById(R.id.btn_event_enter);
        MaterialButton btnEventLeave = v.findViewById(R.id.btn_event_leave);
        MaterialButton btnEventMove  = v.findViewById(R.id.btn_event_move);

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

        // ── Log device filter ────────────────────────────────────────────
        logDeviceAdapter = new android.widget.ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                buildLogDeviceOptions());
        acLogDevice.setAdapter(logDeviceAdapter);
        acLogDevice.setText(LOG_DEVICE_ALL, false);
        acLogDevice.setOnClickListener(view -> acLogDevice.showDropDown());
        acLogDevice.setOnItemClickListener((parent, view, pos, id) -> {
            String picked = parent.getItemAtPosition(pos).toString();
            String filter = LOG_DEVICE_ALL.equals(picked) ? "" : picked;
            adapter.setDeviceFilter(filter);
            updateCount();
        });
        // Refresh option list whenever a new device gets discovered.
        logDiscoveryListener = (name, status, lastSeenMs) -> {
            if (!isAdded() || logDeviceAdapter == null) return;
            java.util.List<String> snap = buildLogDeviceOptions();
            logDeviceAdapter.clear();
            logDeviceAdapter.addAll(snap);
            logDeviceAdapter.notifyDataSetChanged();
        };
        mqtt.addDiscoveryListener(logDiscoveryListener);

        // ── Event filter bar (Zone C) ────────────────────────────────────
        etEventSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                eventAdapter.setSearchQuery(s == null ? "" : s.toString());
                updateCount();
            }
        });
        tgEventType.check(R.id.btn_event_all);
        tgEventType.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            Event.Type filter = null;
            if (id == R.id.btn_event_enter) filter = Event.Type.ENTER;
            else if (id == R.id.btn_event_leave) filter = Event.Type.LEAVE;
            else if (id == R.id.btn_event_move)  filter = Event.Type.MOVE;
            eventAdapter.setTypeFilter(filter);
            updateCount();
        });
    }

    private void showEspLogs() {
        rvLogs.setVisibility(View.VISIBLE);
        rvEvents.setVisibility(View.GONE);
        if (eventFilterBar != null) eventFilterBar.setVisibility(View.GONE);
        if (logFilterBar != null) logFilterBar.setVisibility(View.VISIBLE);
        updateCount();
    }

    private void showEvents() {
        rvLogs.setVisibility(View.GONE);
        rvEvents.setVisibility(View.VISIBLE);
        if (eventFilterBar != null) eventFilterBar.setVisibility(View.VISIBLE);
        if (logFilterBar != null) logFilterBar.setVisibility(View.GONE);
        updateCount();
    }

    /** "(all devices)" + every unique device name we've seen via discovery
     *  OR an existing log entry (covers older devices that haven't published
     *  /info yet but did publish a /log message before). */
    private java.util.List<String> buildLogDeviceOptions() {
        java.util.TreeSet<String> seen = new java.util.TreeSet<>();
        for (MqttService.DiscoveredDevice d : mqtt.getDiscoveredDevices()) {
            if (d != null && d.deviceName != null && !d.deviceName.isEmpty()) {
                seen.add(d.deviceName);
            }
        }
        for (MqttService.LogEntry e : mqtt.getLogBuffer()) {
            if (e != null && e.device != null && !e.device.isEmpty()) seen.add(e.device);
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(LOG_DEVICE_ALL);
        out.addAll(seen);
        return out;
    }

    @Override
    public void onDestroyView() {
        mqtt.removeLogListener(this);
        eventLogger.removeEventListener(this);
        if (logDiscoveryListener != null) {
            mqtt.removeDiscoveryListener(logDiscoveryListener);
            logDiscoveryListener = null;
        }
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
