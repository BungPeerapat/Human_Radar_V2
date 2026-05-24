package com.example.radarhumanapplication;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.radarhumanapplication.profiles.ConnectionProfile;
import com.example.radarhumanapplication.profiles.ProfileManager;
import com.example.radarhumanapplication.recording.SessionRecorder;
import com.example.radarhumanapplication.recording.SessionReplayer;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DashboardFragment extends Fragment
        implements MqttService.ConnectionListener,
                   MqttService.TargetListener,
                   MqttService.StatusListener,
                   MqttService.CmdAckListener,
                   MqttService.DiscoveryListener {

    private TextInputEditText etHost, etPort, etUsername, etPassword;
    /** Device-name field is an exposed-dropdown of currently-discovered
     *  project devices. Still free-typeable when no devices have shown up. */
    private com.google.android.material.textfield.MaterialAutoCompleteTextView etDeviceName;
    private ArrayAdapter<String> deviceNameAdapter;
    private MaterialButton btnConnect;
    private TextView tvMqttStatus, tvDeviceStatus;
    private TextView tvTarget1, tvTarget2, tvTarget3, tvFrameInfo;
    private MaterialButton btnRestart, btnFactoryReset;

    // Profiles UI
    private Spinner spProfiles;
    private MaterialButton btnProfileSave, btnProfileApply, btnProfileDelete;
    private ArrayAdapter<String> profileAdapter;
    private List<ConnectionProfile> profileList = new ArrayList<>();

    // Device Hub UI
    private RecyclerView rvDeviceHub;
    private TextView tvDeviceHubCount, tvDeviceHubEmpty;
    private MaterialButton btnHealthCheckAll;
    private MaterialButton btnPurgeOffline;
    private MaterialButton btnTransportSettings;
    private TextView tvTransportBadge;
    private DeviceHubAdapter deviceHubAdapter;
    private static final String DEVICE_HUB_PREFS = "device_hub_prefs";
    private static final String DEVICE_HUB_PINNED_KEY = "pinned_devices";
    /** Total wait (ms) for an ESP32 to respond to a `health` MQTT probe. The
     *  ESP republishes status+info synchronously on receipt, so a healthy
     *  device round-trips within ~150 ms; the budget here is forgiving for
     *  slow Wi-Fi / hosted broker hops. */
    private static final long HEALTH_TIMEOUT_MS = 3000;

    // Recording UI
    private TextView tvRecStatus;
    private MaterialButton btnRecordStart, btnRecordStop, btnReplayOpen;
    private final Handler recHandler = new Handler(Looper.getMainLooper());
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            updateRecStatus();
            if (SessionRecorder.getInstance().isRecording()
                    || SessionReplayer.getInstance().isReplaying()) {
                recHandler.postDelayed(this, 1000);
            }
        }
    };

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

        spProfiles = v.findViewById(R.id.sp_profiles);
        btnProfileSave = v.findViewById(R.id.btn_profile_save);
        btnProfileApply = v.findViewById(R.id.btn_profile_apply);
        btnProfileDelete = v.findViewById(R.id.btn_profile_delete);

        tvRecStatus = v.findViewById(R.id.tv_rec_status);
        btnRecordStart = v.findViewById(R.id.btn_record_start);
        btnRecordStop = v.findViewById(R.id.btn_record_stop);
        btnReplayOpen = v.findViewById(R.id.btn_replay_open);

        // Device Hub — Multi-device "no thinking" surface.
        // All of these IDs are present in the portrait layout but the
        // landscape layout predates them; null-guard everything so a
        // rotate to landscape doesn't crash with NullPointerException
        // (and so any future layout drift just silently disables that
        // sub-surface instead of taking the whole app down).
        rvDeviceHub           = v.findViewById(R.id.rv_device_hub);
        tvDeviceHubCount      = v.findViewById(R.id.tv_device_hub_count);
        tvDeviceHubEmpty      = v.findViewById(R.id.tv_device_hub_empty);
        btnHealthCheckAll     = v.findViewById(R.id.btn_health_check_all);
        btnPurgeOffline       = v.findViewById(R.id.btn_purge_offline);
        btnTransportSettings  = v.findViewById(R.id.btn_transport_settings);
        tvTransportBadge      = v.findViewById(R.id.tv_transport_badge);
        if (rvDeviceHub != null) {
            rvDeviceHub.setLayoutManager(new LinearLayoutManager(requireContext()));
            deviceHubAdapter = new DeviceHubAdapter(requireContext(),
                    this::onDeviceHubTap,
                    this::onDeviceHubLongPress);
            rvDeviceHub.setAdapter(deviceHubAdapter);
        }
        if (btnHealthCheckAll != null) {
            btnHealthCheckAll.setOnClickListener(x -> onHealthCheckAllClick());
        }
        if (btnPurgeOffline != null) {
            btnPurgeOffline.setOnClickListener(x -> onPurgeOfflineClick());
        }
        if (btnTransportSettings != null) {
            btnTransportSettings.setOnClickListener(x ->
                    com.example.radarhumanapplication.transport.TransportSettingsDialog
                            .show(getParentFragmentManager()));
        }
        if (tvTransportBadge != null) {
            tvTransportBadge.setOnClickListener(x ->
                    com.example.radarhumanapplication.transport.TransportSettingsDialog
                            .show(getParentFragmentManager()));
        }
        refreshTransportBadge();
        // Device-name dropdown: backing adapter that's refreshed every time the
        // discovery feed changes. Tap the field (threshold=0) to see the list.
        deviceNameAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        etDeviceName.setAdapter(deviceNameAdapter);
        etDeviceName.setOnClickListener(view -> {
            if (etDeviceName.isPopupShowing()) etDeviceName.dismissDropDown();
            else etDeviceName.showDropDown();
        });
        refreshDeviceHub();
        refreshDeviceNameDropdown();

        loadPrefs();
        updateConnectButton();
        setupProfiles();
        setupRecording();

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
        mqtt.addDiscoveryListener(this);
        // Refresh the transport badge whenever the user changes mode /
        // toggles the master switch / hides the badge from advanced settings.
        com.example.radarhumanapplication.transport.TransportSettings
                .get(requireContext())
                .addListener(transportSettingsListener);

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
        mqtt.removeDiscoveryListener(this);
        com.example.radarhumanapplication.transport.TransportSettings
                .get(requireContext())
                .removeListener(transportSettingsListener);
        recHandler.removeCallbacks(recTick);
        super.onDestroyView();
    }

    private final com.example.radarhumanapplication.transport.TransportSettings.Listener
            transportSettingsListener = settings -> {
                if (!isAdded()) return;
                refreshTransportBadge();
            };

    @Override
    public void onDeviceDiscovered(String deviceName, String status, long lastSeenMs) {
        if (!isAdded()) return;
        refreshDeviceHub();
        refreshDeviceNameDropdown();
        maybeSuggestSwitch();
        // /info just arrived with a (possibly new) fw — refresh the status
        // line so the version chip updates without waiting for /status.
        String active = mqtt.getDeviceName();
        if (active != null && active.equals(deviceName)) {
            onDeviceStatus(mqtt.getDeviceStatus());
        }
    }

    /** Pull every project-namespaced device from MqttService and load them
     *  into the Device-Name dropdown. Online devices appear first; the user
     *  can still free-type an unseen name. */
    private void refreshDeviceNameDropdown() {
        if (deviceNameAdapter == null) return;
        java.util.List<MqttService.DiscoveredDevice> snap = mqtt.getDiscoveredDevices();
        java.util.List<String> online  = new java.util.ArrayList<>();
        java.util.List<String> offline = new java.util.ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (MqttService.DiscoveredDevice d : snap) {
            if (d == null || d.deviceName == null || d.deviceName.isEmpty()) continue;
            if (!seen.add(d.deviceName)) continue;
            (d.isOnline() ? online : offline).add(d.deviceName);
        }
        java.util.List<String> all = new java.util.ArrayList<>(online.size() + offline.size());
        all.addAll(online);
        all.addAll(offline);
        deviceNameAdapter.clear();
        deviceNameAdapter.addAll(all);
        deviceNameAdapter.notifyDataSetChanged();
    }

    /**
     * No-think auto-suggest: if MQTT is connected but the configured active
     * device has been silent for &gt; 8 s while a different device IS publishing
     * online, surface a Snackbar with a one-tap "SWITCH" action. The user
     * shouldn't have to detect the mismatch themselves.
     */
    private long lastSwitchSuggestionMs = 0;
    private void maybeSuggestSwitch() {
        if (!mqtt.isConnected()) return;
        long now = System.currentTimeMillis();
        if (now - lastSwitchSuggestionMs < 30_000) return;   // throttle

        String active = mqtt.getDeviceName();
        if (active == null || active.isEmpty()) return;

        // The active device — is it currently online via /status?
        MqttService.DiscoveredDevice activeDev = null;
        MqttService.DiscoveredDevice firstOnlineOther = null;
        for (MqttService.DiscoveredDevice d : mqtt.getDiscoveredDevices()) {
            if (d == null) continue;
            if (active.equals(d.deviceName)) {
                activeDev = d;
            } else if (d.isOnline() && firstOnlineOther == null) {
                firstOnlineOther = d;
            }
        }
        boolean activeSilent = (activeDev == null
                || !activeDev.isOnline()
                || (now - activeDev.lastSeenMs) > 8_000);
        if (activeSilent && firstOnlineOther != null) {
            lastSwitchSuggestionMs = now;
            final MqttService.DiscoveredDevice target = firstOnlineOther;
            View root = getView();
            if (root == null) return;
            com.google.android.material.snackbar.Snackbar
                    .make(root, "\"" + target.deviceName + "\" is online — switch?",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction("SWITCH", v -> onDeviceHubTap(target))
                    .show();
        }
    }

    /** Refresh the small "🔄 HYBRID" / "☁ CLOUD" / "📡 LAN" chip in the hub
     *  header. Reflects the user's chosen mode + master-enable. Hidden when
     *  the user disables the transport badge in advanced settings. */
    private void refreshTransportBadge() {
        if (tvTransportBadge == null) return;
        com.example.radarhumanapplication.transport.TransportSettings ts =
                com.example.radarhumanapplication.transport.TransportSettings
                        .get(requireContext());
        if (!ts.isShowBadge()) {
            tvTransportBadge.setVisibility(View.GONE);
            return;
        }
        tvTransportBadge.setVisibility(View.VISIBLE);
        if (!ts.isEnabled()) {
            tvTransportBadge.setText("⏻ OFF");
            tvTransportBadge.setTextColor(getColor(R.color.radar_text_dim));
            return;
        }
        switch (ts.getMode()) {
            case CLOUD:
                tvTransportBadge.setText("☁ CLOUD");
                tvTransportBadge.setTextColor(getColor(R.color.radar_blue));
                break;
            case LAN:
                tvTransportBadge.setText("📡 LAN");
                tvTransportBadge.setTextColor(getColor(R.color.radar_green));
                break;
            case HYBRID:
            default:
                tvTransportBadge.setText("🔄 HYBRID");
                tvTransportBadge.setTextColor(getColor(R.color.radar_yellow));
                break;
        }
    }

    private void refreshDeviceHub() {
        // No hub on this layout (landscape) — skip the whole refresh.
        if (deviceHubAdapter == null || rvDeviceHub == null) return;
        List<MqttService.DiscoveredDevice> snap = mqtt.getDiscoveredDevices();
        java.util.Set<String> pinned = loadPinnedDevices();
        deviceHubAdapter.setEntries(snap, mqtt.getDeviceName(), pinned);
        if (tvDeviceHubCount != null) {
            tvDeviceHubCount.setText(String.format(Locale.US, "%d device%s",
                    snap.size(), snap.size() == 1 ? "" : "s"));
        }
        boolean empty = snap.isEmpty();
        if (tvDeviceHubEmpty != null) {
            tvDeviceHubEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        rvDeviceHub.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private java.util.Set<String> loadPinnedDevices() {
        java.util.Set<String> stored = requireContext()
                .getSharedPreferences(DEVICE_HUB_PREFS, Context.MODE_PRIVATE)
                .getStringSet(DEVICE_HUB_PINNED_KEY, java.util.Collections.emptySet());
        return stored == null ? java.util.Collections.emptySet()
                              : new java.util.HashSet<>(stored);
    }

    private void savePinnedDevices(java.util.Set<String> pinned) {
        requireContext()
                .getSharedPreferences(DEVICE_HUB_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(DEVICE_HUB_PINNED_KEY, new java.util.HashSet<>(pinned))
                .apply();
    }

    /** Long-press a device row — open a PopupMenu with per-device actions. */
    private void onDeviceHubLongPress(MqttService.DiscoveredDevice d, View anchor) {
        if (d == null || d.deviceName == null) return;
        java.util.Set<String> pinned = loadPinnedDevices();
        boolean isPinned = pinned.contains(d.deviceName);

        android.widget.PopupMenu pm =
                new android.widget.PopupMenu(requireContext(), anchor);
        android.view.Menu menu = pm.getMenu();
        menu.add(0, 1, 0, isPinned ? "Unpin" : "📌 Pin to top");
        menu.add(0, 2, 1, "View details");
        menu.add(0, 3, 2, "🩺 Check health");
        menu.add(0, 4, 3, "🔌 Restart device");
        menu.add(0, 5, 4, "🗑️ Delete from broker…");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: togglePinned(d.deviceName); return true;
                case 2: showDeviceDetailsDialog(d);  return true;
                case 3: checkHealthForOne(d);        return true;
                case 4: confirmRestartDevice(d);     return true;
                case 5: confirmRemoveDevice(d);      return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void togglePinned(String name) {
        java.util.Set<String> pinned = loadPinnedDevices();
        if (!pinned.add(name)) pinned.remove(name);
        savePinnedDevices(pinned);
        refreshDeviceHub();
    }

    private void showDeviceDetailsDialog(MqttService.DiscoveredDevice d) {
        if (!isAdded()) return;
        StringBuilder body = new StringBuilder();
        body.append("Name:    ").append(d.deviceName).append('\n');
        body.append("Status:  ").append(d.isOnline() ? "online" : "offline").append('\n');
        if (d.ip  != null && !d.ip.isEmpty())  body.append("IP:      ").append(d.ip).append('\n');
        if (d.fw  != null && !d.fw.isEmpty())  body.append("FW:      v").append(d.fw).append('\n');
        if (d.mac != null && !d.mac.isEmpty()) body.append("MAC:     ").append(d.mac).append('\n');
        long age = (System.currentTimeMillis() - d.lastSeenMs) / 1000L;
        body.append("Last seen: ").append(age).append("s ago");

        new AlertDialog.Builder(requireContext())
                .setTitle("Device details")
                .setMessage(body.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void confirmRestartDevice(MqttService.DiscoveredDevice d) {
        if (!isAdded()) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Restart " + d.deviceName + "?")
                .setMessage("Send a 'restart' MQTT command to this device. "
                        + "It will be offline for ~5 seconds.")
                .setPositiveButton("Restart", (dlg, w) -> {
                    mqtt.sendCommandTo(d.deviceName, "restart");
                    Toast.makeText(requireContext(),
                            "Restart sent to " + d.deviceName,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemoveDevice(MqttService.DiscoveredDevice d) {
        if (!isAdded()) return;
        // Two-option dialog: local-only removal (device reappears next time
        // it publishes) vs broker delete (clears retained /status + /info so
        // a renamed/dead device stops haunting the hub).
        new AlertDialog.Builder(requireContext())
                .setTitle("Remove " + d.deviceName + "?")
                .setMessage("Remove from local hub only — device will reappear "
                        + "when it next publishes.\n\n"
                        + "OR delete from broker — clears retained MQTT "
                        + "messages so stale / renamed devices stop coming "
                        + "back on every reconnect. (The device itself is "
                        + "unaffected; if it's still alive it'll republish.)")
                .setPositiveButton("Delete from broker",
                        (dlg, w) -> performDeviceRemoval(d, /*purgeBroker=*/ true))
                .setNeutralButton("Local only",
                        (dlg, w) -> performDeviceRemoval(d, /*purgeBroker=*/ false))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performDeviceRemoval(MqttService.DiscoveredDevice d, boolean purgeBroker) {
        if (purgeBroker) {
            mqtt.deleteDeviceFromBroker(d.deviceName);
        } else {
            mqtt.removeDiscoveredDevice(d.deviceName);
        }
        java.util.Set<String> pinned = loadPinnedDevices();
        if (pinned.remove(d.deviceName)) savePinnedDevices(pinned);
        // Drop matching saved profiles too.
        ProfileManager pm = ProfileManager.getInstance();
        for (ConnectionProfile p : pm.getProfiles()) {
            if (p != null && d.deviceName.equals(p.deviceName)) {
                pm.deleteProfile(p.id);
            }
        }
        refreshDeviceHub();
        Toast.makeText(requireContext(),
                purgeBroker
                        ? "Deleted " + d.deviceName + " from broker"
                        : "Removed " + d.deviceName + " (local only)",
                Toast.LENGTH_SHORT).show();
    }

    /** Hub-header button: clear every offline device's retained MQTT messages
     *  in one shot. Useful after renaming firmware on several boards. */
    private void onPurgeOfflineClick() {
        if (!isAdded()) return;
        if (!mqtt.isConnected()) {
            Toast.makeText(requireContext(),
                    "Connect to MQTT first", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<MqttService.DiscoveredDevice> offline = new java.util.ArrayList<>();
        for (MqttService.DiscoveredDevice d : mqtt.getDiscoveredDevices()) {
            if (d != null && !d.isOnline()) offline.add(d);
        }
        if (offline.isEmpty()) {
            Toast.makeText(requireContext(),
                    "No offline devices to purge", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder names = new StringBuilder();
        for (MqttService.DiscoveredDevice d : offline) {
            names.append("• ").append(d.deviceName).append('\n');
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Purge " + offline.size() + " offline device"
                        + (offline.size() == 1 ? "" : "s") + "?")
                .setMessage("This clears retained MQTT messages for:\n\n"
                        + names + "\nThey'll only reappear if the actual "
                        + "device republishes — usually they're gone for good.")
                .setPositiveButton("Purge", (dlg, w) -> {
                    int n = 0;
                    java.util.Set<String> pinned = loadPinnedDevices();
                    boolean pinnedDirty = false;
                    for (MqttService.DiscoveredDevice d : offline) {
                        mqtt.deleteDeviceFromBroker(d.deviceName);
                        if (pinned.remove(d.deviceName)) pinnedDirty = true;
                        n++;
                    }
                    if (pinnedDirty) savePinnedDevices(pinned);
                    refreshDeviceHub();
                    Toast.makeText(requireContext(),
                            "Purged " + n + " device" + (n == 1 ? "" : "s")
                                    + " from broker", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Header-card "Check All" button — probe every discovered device and
     *  sweep stale targets for any device that doesn't respond. */
    private void onHealthCheckAllClick() {
        if (!isAdded()) return;
        if (!mqtt.isConnected()) {
            Toast.makeText(requireContext(),
                    "Connect to MQTT first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (com.example.radarhumanapplication.health.HealthCheckManager
                .getInstance().isRunning()) {
            Toast.makeText(requireContext(),
                    "Health check already running", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mqtt.getDiscoveredDevices().isEmpty()) {
            Toast.makeText(requireContext(),
                    "No devices discovered yet", Toast.LENGTH_SHORT).show();
            return;
        }
        btnHealthCheckAll.setEnabled(false);
        btnHealthCheckAll.setText("Checking…");
        boolean started = com.example.radarhumanapplication.health.HealthCheckManager
                .getInstance()
                .checkAll(HEALTH_TIMEOUT_MS, (results, offline) -> {
                    if (!isAdded()) return;
                    btnHealthCheckAll.setEnabled(true);
                    btnHealthCheckAll.setText("Check All");
                    refreshDeviceHub();
                    showHealthAllReport(results, offline);
                });
        if (!started) {
            btnHealthCheckAll.setEnabled(true);
            btnHealthCheckAll.setText("Check All");
            Toast.makeText(requireContext(),
                    "Could not start health check", Toast.LENGTH_SHORT).show();
        }
    }

    /** Per-row health check from the long-press menu. Does NOT sweep on miss
     *  (single-device probes are too noisy to auto-flag offline). */
    private void checkHealthForOne(MqttService.DiscoveredDevice d) {
        if (d == null || d.deviceName == null) return;
        if (!mqtt.isConnected()) {
            Toast.makeText(requireContext(),
                    "Connect to MQTT first", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(requireContext(),
                "Probing " + d.deviceName + "…", Toast.LENGTH_SHORT).show();
        boolean started = com.example.radarhumanapplication.health.HealthCheckManager
                .getInstance()
                .checkOne(d.deviceName, HEALTH_TIMEOUT_MS, (results, offline) -> {
                    if (!isAdded() || results.isEmpty()) return;
                    com.example.radarhumanapplication.health.HealthCheckManager.HealthResult r
                            = results.get(0);
                    if (deviceHubAdapter != null && r.powerLevel >= 0) {
                        deviceHubAdapter.putPowerLevel(r.deviceName, r.powerLevel);
                    }
                    refreshDeviceHub();
                    showHealthOneDialog(r);
                });
        if (!started) {
            Toast.makeText(requireContext(),
                    "Health check busy — try again", Toast.LENGTH_SHORT).show();
        }
    }

    private void showHealthOneDialog(
            com.example.radarhumanapplication.health.HealthCheckManager.HealthResult r) {
        if (!isAdded() || r == null) return;
        com.example.radarhumanapplication.health.PowerHealthFormatter pf =
                null; // static-only
        StringBuilder body = new StringBuilder();
        body.append("Device: ").append(r.deviceName).append('\n');
        body.append(r.responded ? "Status: ✅ ONLINE\n" : "Status: ❌ OFFLINE (no reply in "
                + (HEALTH_TIMEOUT_MS / 1000) + "s)\n");
        if (r.responded) {
            if (!r.fw.isEmpty()) body.append("FW:     v").append(r.fw).append('\n');
            if (!r.ip.isEmpty()) body.append("IP:     ").append(r.ip).append('\n');
            if (r.uptimeSec > 0)  body.append("Uptime: ").append(formatUptime(r.uptimeSec)).append('\n');
            if (r.heapBytes > 0)  body.append("Heap:   ").append(r.heapBytes / 1024).append(" kB\n");
            if (r.rssiDb != 0)    body.append("RSSI:   ").append(r.rssiDb).append(" dBm\n");
            if (r.powerLevel >= 0) {
                body.append('\n');
                body.append("Power: ").append(
                        com.example.radarhumanapplication.health.PowerHealthFormatter
                                .levelEmoji(r.powerLevel)).append(' ');
                body.append(com.example.radarhumanapplication.health.PowerHealthFormatter
                        .levelLabel(r.powerLevel));
                if (r.powerScore >= 0) {
                    body.append("  (score ").append(r.powerScore).append("/100)");
                }
                body.append('\n');
                body.append(com.example.radarhumanapplication.health.PowerHealthFormatter
                        .causeLine(r)).append('\n');
                if (r.bootCount > 0) body.append("Boots:  ").append(r.bootCount).append('\n');
                if (r.brownoutCount > 0) body.append("Brown-outs lifetime: ")
                        .append(r.brownoutCount).append('\n');
                if (!Float.isNaN(r.dieTempC)) body.append("Die temp: ").append(
                        com.example.radarhumanapplication.health.PowerHealthFormatter
                                .formatTemp(r.dieTempC));
            }
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Health check")
                .setMessage(body.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showHealthAllReport(
            java.util.List<com.example.radarhumanapplication.health.HealthCheckManager.HealthResult> results,
            java.util.Set<String> offline) {
        if (!isAdded()) return;
        StringBuilder body = new StringBuilder();
        int online = results.size() - offline.size();
        body.append("Probed ").append(results.size()).append(" device")
                .append(results.size() == 1 ? "" : "s")
                .append(":  ✅ ").append(online).append(" online, ❌ ")
                .append(offline.size()).append(" offline\n\n");
        for (com.example.radarhumanapplication.health.HealthCheckManager.HealthResult r : results) {
            body.append(r.responded ? "✅ " : "❌ ");
            body.append(r.deviceName);
            if (r.responded) {
                body.append("  •  v").append(r.fw.isEmpty() ? "?" : r.fw);
                if (r.uptimeSec > 0) body.append("  up ").append(formatUptime(r.uptimeSec));
                if (r.rssiDb != 0)   body.append("  ").append(r.rssiDb).append(" dBm");
                if (r.powerLevel >= 0) {
                    body.append("\n   Power: ").append(
                            com.example.radarhumanapplication.health.PowerHealthFormatter
                                    .levelEmoji(r.powerLevel)).append(' ');
                    body.append(com.example.radarhumanapplication.health.PowerHealthFormatter
                            .levelLabel(r.powerLevel));
                    if (r.brownoutCount > 0) {
                        body.append("  •  ").append(r.brownoutCount).append(" brown-out")
                                .append(r.brownoutCount == 1 ? "" : "s");
                    }
                    if (!Float.isNaN(r.dieTempC) && r.dieTempC > 70f) {
                        body.append("  •  ").append(
                                com.example.radarhumanapplication.health.PowerHealthFormatter
                                        .formatTemp(r.dieTempC));
                    }
                }
            }
            body.append('\n');
        }
        if (!offline.isEmpty()) {
            body.append("\nStale targets cleared from radar.");
        }
        // Update hub rows so each device row shows the latest power-health chip.
        if (deviceHubAdapter != null) {
            java.util.Map<String, Integer> levels = new java.util.HashMap<>();
            for (com.example.radarhumanapplication.health.HealthCheckManager.HealthResult r
                    : results) {
                if (r.powerLevel >= 0) levels.put(r.deviceName, r.powerLevel);
            }
            deviceHubAdapter.setPowerLevels(levels);
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Health check — all devices")
                .setMessage(body.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private static String formatUptime(long secs) {
        if (secs <= 0) return "?";
        long d = secs / 86400;
        long h = (secs % 86400) / 3600;
        long m = (secs % 3600) / 60;
        long s = secs % 60;
        if (d > 0) return String.format(Locale.US, "%dd %dh", d, h);
        if (h > 0) return String.format(Locale.US, "%dh %dm", h, m);
        if (m > 0) return String.format(Locale.US, "%dm %ds", m, s);
        return s + "s";
    }

    /** Single-tap: make this row the active MQTT subscription target. */
    private void onDeviceHubTap(MqttService.DiscoveredDevice d) {
        if (d == null || d.deviceName == null) return;
        if (d.deviceName.equals(mqtt.getDeviceName())) {
            Toast.makeText(requireContext(),
                    "Already active: " + d.deviceName, Toast.LENGTH_SHORT).show();
            return;
        }
        // Mirror the new name into the manual Device Name field so the user
        // sees what changed, then ask MqttService to re-subscribe.
        if (etDeviceName != null) etDeviceName.setText(d.deviceName);
        mqtt.switchActiveDevice(d.deviceName);
        // Persist for next launch under the same prefs we already use.
        SharedPreferences sp = requireContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putString("device", d.deviceName).apply();
        Toast.makeText(requireContext(),
                "Switched to " + d.deviceName, Toast.LENGTH_SHORT).show();
        refreshDeviceHub();
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
        // Append the device's known firmware version when we have one.
        // Format: "online · v1.0.52" — keeps the status line compact while
        // surfacing fw at all times so the user doesn't need to dig into
        // the hub or long-press details to see what version they're on.
        String suffix = "";
        String name = mqtt.getDeviceName();
        if (name != null && !name.isEmpty()) {
            for (MqttService.DiscoveredDevice d : mqtt.getDiscoveredDevices()) {
                if (d != null && name.equals(d.deviceName)
                        && d.fw != null && !d.fw.isEmpty()) {
                    suffix = "  ·  v" + d.fw;
                    break;
                }
            }
        }
        tvDeviceStatus.setText(status + suffix);
        tvDeviceStatus.setTextColor("online".equals(status) ?
                getColor(R.color.radar_green) : getColor(R.color.radar_red));
        // Active device may have just changed, or a status flipped — refresh
        // the hub so the row's dot and ACTIVE badge stay accurate.
        refreshDeviceHub();
    }

    @Override
    public void onCmdAck(JsonObject ack) {
        if (!isAdded()) return;
        String msg = ack.has("message") ? ack.get("message").getAsString() : "done";
        Toast.makeText(requireContext(), "CMD: " + msg, Toast.LENGTH_SHORT).show();
    }

    // --- Profiles ---

    private void setupProfiles() {
        ProfileManager.getInstance().attach(requireContext());
        profileAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new ArrayList<>());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spProfiles.setAdapter(profileAdapter);
        refreshProfiles();

        spProfiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos < 0 || pos >= profileList.size()) return;
                ProfileManager.getInstance().setActive(profileList.get(pos).id);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnProfileSave.setOnClickListener(x -> promptSaveProfile());
        btnProfileApply.setOnClickListener(x -> applySelectedProfile());
        btnProfileDelete.setOnClickListener(x -> deleteSelectedProfile());
    }

    private void refreshProfiles() {
        profileList = new ArrayList<>(ProfileManager.getInstance().getProfiles());
        List<String> names = new ArrayList<>();
        for (ConnectionProfile p : profileList) {
            names.add(p.name != null ? p.name : "(unnamed)");
        }
        if (names.isEmpty()) names.add("(no profiles)");
        profileAdapter.clear();
        profileAdapter.addAll(names);
        profileAdapter.notifyDataSetChanged();

        String activeId = ProfileManager.getInstance().getActiveId();
        if (activeId != null) {
            for (int i = 0; i < profileList.size(); i++) {
                if (activeId.equals(profileList.get(i).id)) {
                    spProfiles.setSelection(i);
                    break;
                }
            }
        }
    }

    private void promptSaveProfile() {
        TextInputLayout til = new TextInputLayout(requireContext());
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setHint("Profile name");
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setText("Profile " + (profileList.size() + 1));
        til.addView(et);

        new AlertDialog.Builder(requireContext())
                .setTitle("Save profile")
                .setView(til)
                .setPositiveButton("Save", (d, w) -> {
                    String name = et.getText() != null ? et.getText().toString().trim() : "";
                    if (name.isEmpty()) name = "Profile " + (profileList.size() + 1);
                    ConnectionProfile p = ConnectionProfile.create(
                            name,
                            getText(etHost),
                            parseInt(getText(etPort), 1883),
                            getText(etDeviceName),
                            getText(etUsername),
                            getText(etPassword));
                    ProfileManager.getInstance().addProfile(p);
                    ProfileManager.getInstance().setActive(p.id);
                    refreshProfiles();
                    Toast.makeText(requireContext(), "Profile saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applySelectedProfile() {
        int pos = spProfiles.getSelectedItemPosition();
        if (pos < 0 || pos >= profileList.size()) {
            Toast.makeText(requireContext(), "No profile selected", Toast.LENGTH_SHORT).show();
            return;
        }
        ConnectionProfile p = profileList.get(pos);
        etHost.setText(p.brokerHost != null ? p.brokerHost : "");
        etPort.setText(String.valueOf(p.brokerPort));
        etDeviceName.setText(p.deviceName != null ? p.deviceName : "HumanRadar");
        etUsername.setText(p.username != null ? p.username : "");
        etPassword.setText(p.password != null ? p.password : "");
        ProfileManager.getInstance().setActive(p.id);
        Toast.makeText(requireContext(), "Profile applied", Toast.LENGTH_SHORT).show();
    }

    private void deleteSelectedProfile() {
        int pos = spProfiles.getSelectedItemPosition();
        if (pos < 0 || pos >= profileList.size()) return;
        ConnectionProfile p = profileList.get(pos);
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete profile")
                .setMessage("Delete '" + (p.name != null ? p.name : "?") + "'?")
                .setPositiveButton("Delete", (d, w) -> {
                    ProfileManager.getInstance().deleteProfile(p.id);
                    refreshProfiles();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Recording ---

    private void setupRecording() {
        btnRecordStart.setOnClickListener(x -> {
            if (SessionRecorder.getInstance().start(requireContext())) {
                Toast.makeText(requireContext(), "Recording started", Toast.LENGTH_SHORT).show();
                updateRecStatus();
                recHandler.postDelayed(recTick, 1000);
            } else {
                Toast.makeText(requireContext(), "Could not start recording", Toast.LENGTH_SHORT).show();
            }
        });
        btnRecordStop.setOnClickListener(x -> {
            SessionRecorder.getInstance().stop();
            updateRecStatus();
        });
        btnReplayOpen.setOnClickListener(x -> openReplayDialog());
        updateRecStatus();
    }

    private void updateRecStatus() {
        SessionRecorder rec = SessionRecorder.getInstance();
        SessionReplayer rep = SessionReplayer.getInstance();
        if (rec.isRecording()) {
            long secs = (System.currentTimeMillis() - rec.getStartedAtMs()) / 1000L;
            String name = rec.getCurrentFile() != null ? rec.getCurrentFile().getName() : "";
            tvRecStatus.setText(String.format(Locale.US, "Recording %ds — %s", secs, name));
            tvRecStatus.setTextColor(getColor(R.color.radar_red));
        } else if (rep.isReplaying()) {
            int pct = (int) Math.round(rep.getProgress() * 100);
            tvRecStatus.setText(String.format(Locale.US, "Replaying… %d%%", pct));
            tvRecStatus.setTextColor(getColor(R.color.radar_yellow));
        } else {
            tvRecStatus.setText("Idle");
            tvRecStatus.setTextColor(getColor(R.color.radar_text_dim));
        }
    }

    private void openReplayDialog() {
        // Make sure the recorder has a context to find files.
        SessionRecorder.getInstance().listRecordings(requireContext());
        List<File> files = SessionRecorder.getInstance().listRecordings();

        View dlgView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_recordings_list, null, false);
        RecyclerView rv = dlgView.findViewById(R.id.rv_recordings);
        TextView empty = dlgView.findViewById(R.id.tv_recordings_empty);
        MaterialButtonToggleGroup tg = dlgView.findViewById(R.id.tg_speed);
        tg.check(R.id.btn_speed_one);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        final double[] speedRef = new double[]{1.0};
        tg.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_speed_half) speedRef[0] = 0.5;
            else if (checkedId == R.id.btn_speed_two) speedRef[0] = 2.0;
            else speedRef[0] = 1.0;
        });

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Recordings")
                .setView(dlgView)
                .setNegativeButton("Close", null)
                .create();

        if (files.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
        } else {
            empty.setVisibility(View.GONE);
            rv.setVisibility(View.VISIBLE);
            rv.setAdapter(new RecordingAdapter(files, speedRef, dialog));
        }

        dialog.show();
    }

    private class RecordingAdapter extends RecyclerView.Adapter<RecordingAdapter.VH> {
        private final List<File> files;
        private final double[] speedRef;
        private final AlertDialog dialog;
        private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        RecordingAdapter(List<File> files, double[] speedRef, AlertDialog dialog) {
            this.files = files;
            this.speedRef = speedRef;
            this.dialog = dialog;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recording, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            File f = files.get(position);
            h.tvName.setText(f.getName());
            h.tvSize.setText(String.format(Locale.US, "%.1f kB — %s",
                    f.length() / 1024.0, fmt.format(new Date(f.lastModified()))));
            h.btnPlay.setOnClickListener(x -> {
                boolean ok = SessionReplayer.getInstance().loadAndStart(f, speedRef[0]);
                if (ok) {
                    Toast.makeText(requireContext(), "Replay started", Toast.LENGTH_SHORT).show();
                    updateRecStatus();
                    recHandler.postDelayed(recTick, 1000);
                    dialog.dismiss();
                } else {
                    Toast.makeText(requireContext(), "Could not start replay", Toast.LENGTH_SHORT).show();
                }
            });
            h.btnDelete.setOnClickListener(x -> {
                if (SessionRecorder.getInstance().delete(f)) {
                    files.remove(position);
                    notifyItemRemoved(position);
                    // files.size() is already decremented by remove(); use getItemCount()
                    // so the range matches the actual remaining items below position.
                    notifyItemRangeChanged(position, getItemCount() - position);
                } else {
                    Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public int getItemCount() { return files.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvName;
            final TextView tvSize;
            final MaterialButton btnPlay;
            final MaterialButton btnDelete;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_rec_name);
                tvSize = v.findViewById(R.id.tv_rec_size);
                btnPlay = v.findViewById(R.id.btn_rec_play);
                btnDelete = v.findViewById(R.id.btn_rec_delete);
            }
        }
    }

    // --- Helpers ---

    private void updateConnectButton() {
        btnConnect.setText(mqtt.isConnected() ? "DISCONNECT" : "CONNECT");
        btnConnect.setEnabled(true);
    }

    private String getText(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    /** Overload for the device-name exposed-dropdown, which is an
     *  {@link android.widget.AutoCompleteTextView}, not a {@link TextInputEditText}. */
    private String getText(android.widget.AutoCompleteTextView et) {
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
        // First-run defaults point at the hosted broker so a fresh install
        // can connect without the user typing an IP. Saved prefs always win.
        etHost.setText(prefs.getString("host", DEFAULT_BROKER_HOST));
        etPort.setText(String.valueOf(prefs.getInt("port", DEFAULT_BROKER_PORT)));
        etDeviceName.setText(prefs.getString("device", "HumanRadar"));
        etUsername.setText(prefs.getString("user", ""));
        etPassword.setText(prefs.getString("pass", ""));
    }

    private static final String DEFAULT_BROKER_HOST = "119.59.99.155";
    private static final int    DEFAULT_BROKER_PORT = 8883;
}
