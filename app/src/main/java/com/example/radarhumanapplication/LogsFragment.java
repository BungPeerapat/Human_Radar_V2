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
    private MaterialSwitch swAutoFetch;
    private TextView tvLogCount;
    private MqttService mqtt;
    /** Smart-scroll state: true until the user manually scrolls away from
     *  the bottom. When false, incoming logs do NOT snap the viewport back,
     *  so the user can read older lines without them jumping under their
     *  fingers. Resets the moment the user scrolls back to the bottom. */
    private boolean logsStickToBottom = true;
    private boolean eventsStickToBottom = true;
    /** Periodic timer that pulls {@code get_log_buffer} from the ESP. Runs
     *  only while {@link #swAutoFetch} is on AND MQTT is connected. */
    private final android.os.Handler autoFetchHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long AUTO_FETCH_INTERVAL_MS = 3_000;
    private static final int  AUTO_FETCH_LIMIT       = 30;
    private final Runnable autoFetchTick = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            if (swAutoFetch != null && swAutoFetch.isChecked() && mqtt.isConnected()) {
                com.google.gson.JsonObject extras = new com.google.gson.JsonObject();
                extras.addProperty("limit", AUTO_FETCH_LIMIT);
                mqtt.sendCommand("get_log_buffer", extras);
            }
            autoFetchHandler.postDelayed(this, AUTO_FETCH_INTERVAL_MS);
        }
    };
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
        swAutoFetch  = v.findViewById(R.id.sw_auto_fetch);
        tvLogCount = v.findViewById(R.id.tv_log_count);
        MaterialButton btnFetch = v.findViewById(R.id.btn_fetch_logs);
        MaterialButton btnCopy  = v.findViewById(R.id.btn_copy_logs);
        MaterialButton btnShare = v.findViewById(R.id.btn_share_logs);
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

        // Smart-scroll: only stick to bottom when the user is actually
        // looking at the bottom. The moment they scroll up to read older
        // entries, we stop snapping the viewport back under them. They
        // re-arm the sticky behavior just by scrolling all the way back.
        rvLogs.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // canScrollVertically(1) == false  ⇒  at the bottom edge.
                logsStickToBottom = !rv.canScrollVertically(1);
            }
        });
        rvEvents.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                eventsStickToBottom = !rv.canScrollVertically(1);
            }
        });

        // Auto-fetch toggle: drives a periodic get_log_buffer cmd so the
        // viewer stays warm without the user pressing Fetch every minute.
        swAutoFetch.setOnCheckedChangeListener((b, on) -> {
            autoFetchHandler.removeCallbacks(autoFetchTick);
            if (on) autoFetchHandler.postDelayed(autoFetchTick, 500);
        });

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

        btnCopy.setOnClickListener(x -> {
            String text = buildLogTextForExport();
            if (text.isEmpty()) {
                android.widget.Toast.makeText(requireContext(),
                        "Nothing to copy", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "HumanRadar logs", text));
            android.widget.Toast.makeText(requireContext(),
                    "Copied " + text.split("\n").length + " lines to clipboard",
                    android.widget.Toast.LENGTH_SHORT).show();
        });

        btnShare.setOnClickListener(x -> {
            String text = buildLogTextForExport();
            if (text.isEmpty()) {
                android.widget.Toast.makeText(requireContext(),
                        "Nothing to share", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            android.content.Intent send = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_SUBJECT,
                    "HumanRadar logs " + new java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(new java.util.Date()));
            send.putExtra(android.content.Intent.EXTRA_TEXT, text);
            startActivity(android.content.Intent.createChooser(send, "Share logs"));
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
        autoFetchHandler.removeCallbacks(autoFetchTick);
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
                && swAutoScroll.isChecked()
                && logsStickToBottom
                && adapter.getItemCount() > 0) {
            rvLogs.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    @Override
    public void onEvent(Event e) {
        if (!isAdded()) return;
        eventAdapter.addEntry(e);
        updateCount();
        if (rvEvents.getVisibility() == View.VISIBLE
                && swAutoScroll.isChecked()
                && eventsStickToBottom
                && eventAdapter.getItemCount() > 0) {
            rvEvents.scrollToPosition(eventAdapter.getItemCount() - 1);
        }
    }

    /**
     * Build a plain-text dump of whichever list is currently on screen, with
     * a header carrying everything a developer typically asks for when
     * triaging a report: app version, current device + IP + fw, MQTT broker
     * + connection state, transport mode + lane snapshot, timestamps.
     *
     * <p>If the ESP-logs tab is showing and a device filter is active, only
     * that device's entries are exported.
     */
    private String buildLogTextForExport() {
        StringBuilder sb = new StringBuilder(8 * 1024);
        java.text.SimpleDateFormat ts = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
        sb.append("===== HumanRadar log export =====\n");
        sb.append("Captured:    ").append(ts.format(new java.util.Date())).append('\n');
        sb.append("App version: v").append(BuildConfig.VERSION_NAME)
                .append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Android:     ").append(android.os.Build.MANUFACTURER)
                .append(' ').append(android.os.Build.MODEL)
                .append(" / API ").append(android.os.Build.VERSION.SDK_INT).append('\n');

        // Active MQTT context.
        sb.append("Broker:      ").append(mqtt.getBrokerHost())
                .append(':').append(mqtt.getBrokerPort())
                .append(mqtt.isConnected() ? "  [connected]" : "  [DISCONNECTED]").append('\n');
        sb.append("Active dev:  ").append(emptyAsDash(mqtt.getDeviceName()))
                .append("   status=").append(emptyAsDash(mqtt.getDeviceStatus())).append('\n');

        // Discovered devices snapshot.
        java.util.List<MqttService.DiscoveredDevice> discovered = mqtt.getDiscoveredDevices();
        sb.append("Discovered:  ").append(discovered.size()).append(" device(s)\n");
        for (MqttService.DiscoveredDevice d : discovered) {
            if (d == null) continue;
            sb.append("  - ").append(d.deviceName)
                    .append("   ip=").append(emptyAsDash(d.ip))
                    .append("   fw=").append(emptyAsDash(d.fw))
                    .append("   status=").append(emptyAsDash(d.status))
                    .append("   age=").append(
                            (System.currentTimeMillis() - d.lastSeenMs) / 1000L).append("s\n");
        }

        // Transport lane snapshot (Cloud / LAN / Hybrid).
        try {
            com.example.radarhumanapplication.transport.TransportSettings ts2 =
                    com.example.radarhumanapplication.transport.TransportSettings
                            .get(requireContext());
            sb.append("Transport:   ").append(ts2.getMode())
                    .append(ts2.isEnabled() ? "" : " [DISABLED]").append('\n');
            com.example.radarhumanapplication.transport.HybridTransportManager mgr =
                    com.example.radarhumanapplication.transport.HybridTransportManager
                            .get(requireContext());
            java.util.Map<String,
                    com.example.radarhumanapplication.transport.HybridTransportManager
                            .DeviceTransportStatus> snap = mgr.snapshot();
            for (java.util.Map.Entry<String,
                    com.example.radarhumanapplication.transport.HybridTransportManager
                            .DeviceTransportStatus> e : snap.entrySet()) {
                com.example.radarhumanapplication.transport.HybridTransportManager
                        .DeviceTransportStatus s = e.getValue();
                sb.append("  - ").append(e.getKey())
                        .append("   lane=").append(s.lane)
                        .append("   wsState=").append(s.wsState)
                        .append("   ip=").append(emptyAsDash(s.ip)).append('\n');
            }
        } catch (Exception ignored) {}

        sb.append("\n");
        if (rvEvents != null && rvEvents.getVisibility() == View.VISIBLE) {
            sb.append("--- Detection events (").append(eventAdapter.getItemCount())
                    .append(") ---\n");
            for (Event ev : eventLogger.getEvents()) {
                sb.append(ts.format(new java.util.Date(ev.timestamp))).append(' ')
                        .append(ev.type == null ? "?" : ev.type.name()).append("  ")
                        .append(ev.description == null ? "" : ev.description).append('\n');
            }
        } else {
            // ESP-logs view. Honor the device filter so a shared dump matches
            // what the user actually saw on screen.
            String filter = (acLogDevice == null || acLogDevice.getText() == null)
                    ? "" : acLogDevice.getText().toString();
            boolean filterAll = filter.isEmpty() || LOG_DEVICE_ALL.equals(filter);
            java.util.List<MqttService.LogEntry> buf = mqtt.getLogBuffer();
            sb.append("--- ESP32 logs (").append(buf.size())
                    .append(filterAll ? ", all devices" : (", filter=" + filter))
                    .append(") ---\n");
            for (MqttService.LogEntry e : buf) {
                if (!filterAll && !filter.equals(e.device)) continue;
                sb.append('[').append(e.timestamp).append("] ")
                        .append('[').append(pad(e.level, 5)).append("] ");
                if (e.device != null && !e.device.isEmpty()) {
                    sb.append(e.device).append(' ');
                }
                if (e.tag != null && !e.tag.isEmpty()) {
                    sb.append('[').append(e.tag).append("] ");
                }
                sb.append(e.message == null ? "" : e.message);
                if (e.freeHeap > 0)  sb.append("   heap=").append(e.freeHeap);
                if (e.uptime  > 0)   sb.append("   up=").append(e.uptime).append('s');
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String emptyAsDash(String s) {
        return (s == null || s.isEmpty()) ? "—" : s;
    }

    private static String pad(String s, int n) {
        if (s == null) s = "?";
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private void updateCount() {
        if (rvEvents.getVisibility() == View.VISIBLE) {
            tvLogCount.setText(eventAdapter.getItemCount() + " events");
        } else {
            tvLogCount.setText(adapter.getItemCount() + " entries");
        }
    }
}
