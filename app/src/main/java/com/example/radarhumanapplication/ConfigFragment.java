package com.example.radarhumanapplication;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.radarhumanapplication.BuildConfig;
import com.example.radarhumanapplication.alerts.AlertHttpClient;
import com.example.radarhumanapplication.alerts.AlertPatternConfig;
import com.example.radarhumanapplication.alerts.DeviceStatusAlertConfig;
import com.example.radarhumanapplication.alerts.DeviceStatusAlertManager;
import com.example.radarhumanapplication.profiles.ConnectionProfile;
import com.example.radarhumanapplication.profiles.DevicePickerDialog;
import com.example.radarhumanapplication.profiles.ProfileManager;
import com.example.radarhumanapplication.update.FirmwareUploader;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.example.radarhumanapplication.update.UpdateDialog;
import com.example.radarhumanapplication.update.UpdateManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ConfigFragment extends Fragment implements MqttService.ConfigAckListener {

    private com.google.android.material.textfield.MaterialAutoCompleteTextView cfgDeviceName;
    private TextInputEditText cfgPublishInterval, cfgUnmannedDelay, cfgTargetTimeout;
    private android.widget.ArrayAdapter<String> cfgDeviceNameAdapter;
    private MqttService.DiscoveryListener cfgDiscoveryListener;
    private MaterialSwitch cfgMultiTarget;
    private Slider cfgSensitivity;
    private MaterialButton btnSendConfig, btnCheckUpdate;
    private TextView tvConfigAck, tvAppVersion;
    private MqttService mqtt;

    // Alert (GPIO26) UI
    private MaterialSwitch alertEnable, alertLedWifi;
    private TextInputEditText alertShortMs, alertLongMs, alertGapMs, alertDebounceMs, alertMaxRange, alertDeviceIp;
    private RadioGroup alertSoundType;
    private RadioButton alertSoundTone, alertSoundWav, alertSoundTts;
    private RadioButton alertRoutingNotif, alertRoutingAlarm;
    private Slider alertVolume;
    private TextView alertVolumeLabel, alertStatus;
    private MaterialButton btnAlertFetch, btnAlertPush, btnAlertTestLocal, btnAlertTestRemote;
    private MaterialButton btnPickDeviceIp;
    private MaterialButton btnPickShort, btnPickLong;

    // WiFi Setup UI
    private RadioGroup wifiMode;
    private RadioButton wifiModeSta, wifiModeAp;
    private com.google.android.material.textfield.MaterialAutoCompleteTextView wifiSsid;
    private TextInputEditText wifiPass;
    private MaterialButton btnWifiFetch, btnWifiApply;
    private TextView wifiStatus;
    private final WifiScanHelper wifiScanHelper = new WifiScanHelper();
    private android.widget.ArrayAdapter<String> wifiSsidAdapter;
    private ActivityResultLauncher<String[]> wifiPermLauncher;
    private TextView alertShortUriLabel, alertLongUriLabel;
    private String pickedShortUri = "";
    private String pickedLongUri  = "";
    private ActivityResultLauncher<String[]> pickShortLauncher;
    private ActivityResultLauncher<String[]> pickLongLauncher;
    private final AlertHttpClient alertHttp = new AlertHttpClient();
    private static final String ALERT_PREFS = "alert_prefs";

    // Device online/offline notification UI
    private MaterialSwitch devOnlineEnable, devOfflineEnable;
    private TextView devDeviceLabel, devOnlineUriLabel, devOfflineUriLabel, devAlertStatus;
    private MaterialButton btnPickOnline, btnPickOffline, btnTestOnline, btnTestOffline;
    private String devOnlineUri = "";
    private String devOfflineUri = "";
    private ActivityResultLauncher<String[]> pickOnlineLauncher;
    private ActivityResultLauncher<String[]> pickOfflineLauncher;
    /** Device name currently being configured in the online/offline card (defaults to active MQTT device). */
    private String devAlertEditingDevice = "";

    // ESP32 Firmware OTA UI
    private MaterialButton btnFwCheck, btnFwPick, btnFwUpload;
    private TextView fwPickedLabel, fwStatus;
    private TextView fwProgressPercent, fwProgressPhase;
    private LinearProgressIndicator fwProgress;
    private Uri pickedFirmwareUri;
    private long pickedFirmwareSize;
    private String pickedFirmwareName = "";
    private ActivityResultLauncher<String[]> pickFirmwareLauncher;
    private FirmwareUploader firmwareUploader;
    private com.example.radarhumanapplication.update.FirmwareUpdater firmwareUpdater;
    private MaterialButton btnFwSkipVersion, btnFwRollback;
    private MaterialSwitch swBetaChannel;
    /** Manifest from the most recent CHECK FIRMWARE UPDATE — needed for Skip. */
    private com.example.radarhumanapplication.update.FirmwareManifest lastCheckedManifest;
    private static final String FW_SKIP_PREFS = "fw_skip_prefs";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickShortLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> onSoundPicked(uri, true));
        pickLongLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> onSoundPicked(uri, false));
        pickOnlineLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> onDeviceSoundPicked(uri, true));
        pickOfflineLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> onDeviceSoundPicked(uri, false));
        pickFirmwareLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onFirmwarePicked);
        // WiFi-scan permission flips per SDK level — RequestMultiplePermissions
        // handles both ACCESS_FINE_LOCATION (<=API 32) and NEARBY_WIFI_DEVICES
        // (API 33+) without forcing us to branch at registration time.
        wifiPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                granted -> {
                    boolean ok = !granted.isEmpty();
                    for (Boolean b : granted.values()) if (!Boolean.TRUE.equals(b)) ok = false;
                    if (ok) {
                        triggerWifiScan(true);
                    } else {
                        setWifiStatus("Permission denied — type the SSID manually",
                                true);
                    }
                });
    }

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
        // Populate the dropdown with MQTT-discovered device names and keep it in
        // sync as more devices come online. The field is still editable so a
        // brand-new install (no discoveries yet) can type a name manually.
        cfgDeviceNameAdapter = new android.widget.ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                collectDiscoveredDeviceNames());
        cfgDeviceName.setAdapter(cfgDeviceNameAdapter);
        // Force the dropdown to open on focus / tap of the arrow.
        cfgDeviceName.setOnClickListener(view -> cfgDeviceName.showDropDown());
        cfgDeviceName.setOnFocusChangeListener((view, has) -> {
            if (has) cfgDeviceName.showDropDown();
        });
        cfgDiscoveryListener = (name, status, lastSeenMs) -> {
            if (!isAdded() || cfgDeviceNameAdapter == null) return;
            java.util.List<String> snap = collectDiscoveredDeviceNames();
            cfgDeviceNameAdapter.clear();
            cfgDeviceNameAdapter.addAll(snap);
            cfgDeviceNameAdapter.notifyDataSetChanged();
        };
        mqtt.addDiscoveryListener(cfgDiscoveryListener);
        tvAppVersion.setText("Current: v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");

        btnSendConfig.setOnClickListener(this::onSendConfig);
        btnCheckUpdate.setOnClickListener(this::onCheckUpdate);
        mqtt.addConfigAckListener(this);

        bindAlertUi(v);
        bindDeviceStatusAlertUi(v);
        bindFirmwareOtaUi(v);
        bindWifiSetupUi(v);
    }

    // ------------------------------------------------------------------
    //  WiFi Setup card
    // ------------------------------------------------------------------
    private void bindWifiSetupUi(View v) {
        wifiMode    = v.findViewById(R.id.wifi_mode);
        wifiModeSta = v.findViewById(R.id.wifi_mode_sta);
        wifiModeAp  = v.findViewById(R.id.wifi_mode_ap);
        wifiSsid    = v.findViewById(R.id.wifi_ssid);
        wifiPass    = v.findViewById(R.id.wifi_pass);
        btnWifiFetch = v.findViewById(R.id.btn_wifi_fetch);
        btnWifiApply = v.findViewById(R.id.btn_wifi_apply);
        wifiStatus   = v.findViewById(R.id.wifi_status);

        btnWifiFetch.setOnClickListener(view -> onWifiFetch());
        btnWifiApply.setOnClickListener(view -> onWifiApply());

        // SSID dropdown — empty until first scan. Seed with the currently-saved
        // WiFi (if any) so the user sees something even before scanning.
        wifiSsidAdapter = new android.widget.ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                new java.util.ArrayList<>());
        wifiSsid.setAdapter(wifiSsidAdapter);
        // Scan once when the user focuses or taps the field; cached results are
        // reused for 30 s so subsequent taps feel instant.
        wifiSsid.setOnClickListener(view -> triggerWifiScan(false));
        wifiSsid.setOnFocusChangeListener((view, has) -> { if (has) triggerWifiScan(false); });
    }

    /**
     * Scan the phone's nearby WiFi and populate the SSID dropdown.
     * If the runtime permission is missing it asks for it first (user can deny —
     * the field stays free-text). When {@code force} is true, ignore the helper
     * cache so the result always reflects a fresh scan.
     */
    private void triggerWifiScan(boolean force) {
        if (!WifiScanHelper.hasRequiredPermission(requireContext())) {
            try {
                wifiPermLauncher.launch(WifiScanHelper.requiredPermissions());
            } catch (Exception e) {
                setWifiStatus("Could not request permission: " + e.getMessage(), true);
            }
            return;
        }
        if (force) wifiScanHelper.cancel(requireContext());
        setWifiStatus("Scanning nearby WiFi…", false);
        wifiScanHelper.scan(requireContext(), (ssids, err) -> {
            if (!isAdded()) return;
            if (err != null && !err.isEmpty()) {
                setWifiStatus("Scan: " + err, true);
            }
            if (ssids == null || ssids.isEmpty()) {
                if (err == null || err.isEmpty()) {
                    setWifiStatus("No nearby networks found — type the SSID manually",
                            false);
                }
                return;
            }
            wifiSsidAdapter.clear();
            wifiSsidAdapter.addAll(ssids);
            wifiSsidAdapter.notifyDataSetChanged();
            wifiSsid.showDropDown();
            setWifiStatus("Found " + ssids.size() + " nearby network"
                    + (ssids.size() == 1 ? "" : "s"), false);
        });
    }

    /** GET /api/config and pre-fill the SSID + mode fields. Password is NOT
     *  pre-filled — even though /api/config returns it, blanking the field
     *  keeps the user from accidentally re-saving a stale string. */
    private void onWifiFetch() {
        String typedIp = getText(alertDeviceIp);
        if (!typedIp.isEmpty()) {
            doWifiFetch(typedIp);
            return;
        }
        DevicePickerDialog.show(requireContext(), "Fetch WiFi config from?", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                offerManualIpFallback(p.name == null ? "device" : p.name, ip -> {
                    alertDeviceIp.setText(ip);
                    doWifiFetch(ip);
                });
                return;
            }
            alertDeviceIp.setText(p.espHttpIp);
            doWifiFetch(p.espHttpIp);
        });
    }

    private void doWifiFetch(String ip) {
        setWifiStatus("Fetching from " + ip + "…", false);
        alertHttp.fetchDeviceConfig(ip, (cfg, err) -> {
            if (!isAdded()) return;
            if (cfg == null) {
                setWifiStatus("Fetch failed: " + err, true);
                return;
            }
            int wm = cfg.has("wm") ? cfg.get("wm").getAsInt() : 0;
            String ws = cfg.has("ws") ? cfg.get("ws").getAsString() : "";
            // Don't echo the password back into the UI — see method-level comment.
            // setText(s, false) — second arg = filter; we don't want to clamp the
            // dropdown adapter to only items containing the fetched SSID string.
            wifiSsid.setText(ws, false);
            wifiPass.setText("");
            if (wm == 1) wifiModeSta.setChecked(true);
            else         wifiModeAp.setChecked(true);
            setWifiStatus("Fetched · mode=" + (wm == 1 ? "STA" : "AP")
                    + " · SSID=" + (ws.isEmpty() ? "(none)" : ws), false);
        });
    }

    private void onWifiApply() {
        boolean sta = wifiModeSta.isChecked();
        String ssid = wifiSsid.getText() != null
                ? wifiSsid.getText().toString().trim() : "";
        String pass = wifiPass.getText() != null ? wifiPass.getText().toString() : "";
        if (sta && ssid.isEmpty()) {
            setWifiStatus("STA mode needs a non-empty SSID", true);
            return;
        }
        String typedIp = getText(alertDeviceIp);
        if (!typedIp.isEmpty()) {
            confirmWifiApply(typedIp, "(manual)", sta ? 1 : 0, ssid, pass);
            return;
        }
        DevicePickerDialog.show(requireContext(), "Update WiFi on which device?", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                offerManualIpFallback(p.name == null ? "device" : p.name, ip -> {
                    alertDeviceIp.setText(ip);
                    confirmWifiApply(ip, p.name, sta ? 1 : 0, ssid, pass);
                });
                return;
            }
            alertDeviceIp.setText(p.espHttpIp);
            confirmWifiApply(p.espHttpIp, p.name, sta ? 1 : 0, ssid, pass);
        });
    }

    private void confirmWifiApply(String ip, String label, int mode,
                                  String ssid, String pass) {
        String summary = mode == 1
                ? "Switch ESP32 at " + ip + " (" + label + ") to STA mode and join:\n"
                        + "  SSID: " + ssid + "\n"
                        + "  Password: " + (pass.isEmpty() ? "(open network)"
                                                            : "********")
                : "Switch ESP32 at " + ip + " (" + label + ") to AP mode.";
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Apply WiFi & reboot?")
                .setMessage(summary + "\n\nThe device will reboot. If the new WiFi "
                        + "is wrong, the device falls back to AP mode after ~10 s.")
                .setPositiveButton("Apply", (d, w) -> doWifiApply(ip, mode, ssid, pass))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doWifiApply(String ip, int mode, String ssid, String pass) {
        btnWifiApply.setEnabled(false);
        btnWifiFetch.setEnabled(false);
        // Workaround for firmware < v1.0.29: /api/config used to overwrite every
        // section (MQTT, sensor) even if the body only carried WiFi keys, wiping
        // unrelated settings. Fetch the current config first and merge the new
        // WiFi values on top so the POST is byte-for-byte identical to the live
        // config except for wm/ws/wp.
        setWifiStatus("Fetching current config to preserve other settings…", false);
        alertHttp.fetchDeviceConfig(ip, (current, fetchErr) -> {
            if (!isAdded()) return;
            if (current == null) {
                // Fall back to plain WiFi-only POST — newer firmware (v1.0.29+) handles
                // partial updates correctly so this still works there.
                android.util.Log.w("ConfigFragment",
                        "fetchDeviceConfig failed, falling back to partial POST: "
                        + fetchErr);
                pushWifiOnly(ip, mode, ssid, pass);
                return;
            }
            pushWifiMerged(ip, current, mode, ssid, pass);
        });
    }

    private void pushWifiMerged(String ip, com.google.gson.JsonObject current,
                                int mode, String ssid, String pass) {
        // Build a body with EVERY key the firmware's handleSaveConfig reads,
        // sourced from `current` except for wm/ws/wp which come from the UI.
        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        body.addProperty("wm", mode);
        body.addProperty("ws", ssid == null ? "" : ssid);
        body.addProperty("wp", pass == null ? "" : pass);
        copyIntIfPresent(current, body, "me");
        copyIntIfPresent(current, body, "mr");
        copyStrIfPresent(current, body, "mh");
        copyIntIfPresent(current, body, "mp");
        copyStrIfPresent(current, body, "mu");
        copyStrIfPresent(current, body, "mpp");
        copyStrIfPresent(current, body, "dn");
        copyIntIfPresent(current, body, "pi");
        copyIntIfPresent(current, body, "ud");
        copyIntIfPresent(current, body, "tt");
        copyIntIfPresent(current, body, "mt");
        copyIntIfPresent(current, body, "sn");

        setWifiStatus("Sending merged config to " + ip + "…", false);
        alertHttp.postRawConfig(ip, body, (ok, err) ->
                handleWifiApplyResult(ok, err, mode, ssid));
    }

    private void pushWifiOnly(String ip, int mode, String ssid, String pass) {
        setWifiStatus("Sending WiFi config to " + ip + "…", false);
        alertHttp.updateWifi(ip, mode, ssid, pass, (ok, err) ->
                handleWifiApplyResult(ok, err, mode, ssid));
    }

    private void handleWifiApplyResult(Boolean ok, String err,
                                       int mode, String ssid) {
        if (!isAdded()) return;
        btnWifiApply.setEnabled(true);
        btnWifiFetch.setEnabled(true);
        if (Boolean.TRUE.equals(ok)) {
            setWifiStatus("WiFi saved · device is rebooting…", false);
            wifiPass.setText("");
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("WiFi config sent")
                    .setMessage("The ESP32 is rebooting. If it connects to "
                            + (mode == 1 ? "\"" + ssid + "\"" : "AP mode")
                            + " successfully you will see it publish "
                            + "online + /info via MQTT within ~30 s. If not, "
                            + "it falls back to AP mode \"HumanRadar\" "
                            + "(open network) automatically.")
                    .setPositiveButton("OK", null)
                    .show();
        } else {
            setWifiStatus("Apply failed: " + err, true);
        }
    }

    private static void copyIntIfPresent(com.google.gson.JsonObject src,
                                         com.google.gson.JsonObject dst,
                                         String key) {
        if (src != null && src.has(key) && !src.get(key).isJsonNull()) {
            try { dst.addProperty(key, src.get(key).getAsInt()); }
            catch (Exception ignored) {}
        }
    }

    private static void copyStrIfPresent(com.google.gson.JsonObject src,
                                         com.google.gson.JsonObject dst,
                                         String key) {
        if (src != null && src.has(key) && !src.get(key).isJsonNull()) {
            try { dst.addProperty(key, src.get(key).getAsString()); }
            catch (Exception ignored) {}
        }
    }

    private void setWifiStatus(String text, boolean error) {
        wifiStatus.setText(text);
        wifiStatus.setTextColor(requireContext().getColor(
                error ? R.color.radar_red : R.color.radar_green));
    }

    // ------------------------------------------------------------------
    //  ESP32 Firmware OTA card
    // ------------------------------------------------------------------
    private void bindFirmwareOtaUi(View v) {
        btnFwCheck    = v.findViewById(R.id.btn_fw_check);
        btnFwPick     = v.findViewById(R.id.btn_fw_pick);
        btnFwUpload   = v.findViewById(R.id.btn_fw_upload);
        fwPickedLabel = v.findViewById(R.id.fw_picked_label);
        fwProgress    = v.findViewById(R.id.fw_progress);
        fwProgressPercent = v.findViewById(R.id.fw_progress_percent);
        fwProgressPhase   = v.findViewById(R.id.fw_progress_phase);
        fwStatus      = v.findViewById(R.id.fw_status);
        firmwareUploader = new FirmwareUploader(requireContext());
        firmwareUpdater  = new com.example.radarhumanapplication.update.FirmwareUpdater(requireContext());

        btnFwSkipVersion = v.findViewById(R.id.btn_fw_skip_version);
        btnFwRollback    = v.findViewById(R.id.btn_fw_rollback);
        swBetaChannel    = v.findViewById(R.id.sw_beta_channel);
        swBetaChannel.setChecked(requireContext()
                .getSharedPreferences(FW_SKIP_PREFS, 0)
                .getBoolean("beta", false));
        swBetaChannel.setOnCheckedChangeListener((b, c) -> {
            requireContext().getSharedPreferences(FW_SKIP_PREFS, 0)
                    .edit().putBoolean("beta", c).apply();
            setFwStatus("Beta channel " + (c ? "enabled" : "disabled")
                    + " (applies to next CHECK FIRMWARE UPDATE)", false);
        });
        btnFwSkipVersion.setOnClickListener(view -> onSkipFirmwareVersion());
        btnFwRollback.setOnClickListener(view -> onRollbackFirmware());

        btnFwCheck.setOnClickListener(view -> onCheckFirmwareUpdate());

        btnFwPick.setOnClickListener(view -> {
            try {
                // ESP32 .bin files have no standard MIME type — accept anything binary.
                pickFirmwareLauncher.launch(new String[]{
                        "application/octet-stream",
                        "application/macbinary",
                        "*/*"
                });
            } catch (Exception e) {
                setFwStatus("No file picker available: " + e.getMessage(), true);
            }
        });

        btnFwUpload.setOnClickListener(view -> startFirmwareUpload());
    }

    private void onFirmwarePicked(@Nullable Uri uri) {
        if (uri == null) return;
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {}

        pickedFirmwareUri  = uri;
        pickedFirmwareName = lastSegment(uri.toString());
        pickedFirmwareSize = queryFileSize(uri);

        String sizeText = pickedFirmwareSize > 0
                ? String.format(java.util.Locale.US, " (%.1f KB)", pickedFirmwareSize / 1024.0)
                : "";
        fwPickedLabel.setText("Firmware file: " + pickedFirmwareName + sizeText);
        btnFwUpload.setEnabled(true);
        setFwStatus("Ready to upload", false);
    }

    private long queryFileSize(Uri uri) {
        try (android.database.Cursor c = requireContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private void startFirmwareUpload() {
        if (pickedFirmwareUri == null) {
            setFwStatus("Pick a firmware .bin first", true);
            return;
        }
        String typedIp = getText(alertDeviceIp);
        if (!typedIp.isEmpty()) {
            confirmFirmwareUpload(typedIp, "(manual)");
            return;
        }
        DevicePickerDialog.show(requireContext(), "Flash which ESP32?", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                offerManualIpFallback(p.name == null ? "device" : p.name, ip -> {
                    alertDeviceIp.setText(ip);
                    confirmFirmwareUpload(ip, p.name);
                });
                return;
            }
            alertDeviceIp.setText(p.espHttpIp);
            confirmFirmwareUpload(p.espHttpIp, p.name);
        });
    }

    private void confirmFirmwareUpload(String ip, String label) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Flash firmware?")
                .setMessage("Upload\n  " + pickedFirmwareName
                        + "\nto ESP32 at " + ip + " (" + label + ")?\n\n"
                        + "The device will reboot into the new firmware after verification. "
                        + "Do not power off during upload.")
                .setPositiveButton("Upload", (d, w) -> doFirmwareUpload(ip))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doFirmwareUpload(String ip) {
        btnFwUpload.setEnabled(false);
        btnFwPick.setEnabled(false);
        showFwProgress("Uploading to " + ip, 0);
        setFwStatus("Uploading to " + ip + "...", false);

        firmwareUploader.start(ip, pickedFirmwareUri, pickedFirmwareSize,
                new FirmwareUploader.Callback() {
                    @Override public void onProgress(int percent) {
                        if (!isAdded()) return;
                        showFwProgress("Uploading to " + ip, percent);
                        setFwStatus("Uploading… " + percent + "%", false);
                    }

                    @Override public void onCompleted() {
                        if (!isAdded()) return;
                        showFwProgress("Upload complete", 100);
                        btnFwPick.setEnabled(true);
                        btnFwUpload.setEnabled(true);
                        setFwStatus("Upload complete. ESP32 is rebooting...", false);
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Firmware uploaded")
                                .setMessage("The ESP32 is rebooting into the new firmware. "
                                        + "The MQTT connection will reconnect automatically "
                                        + "once the device is back online.")
                                .setPositiveButton("OK", null)
                                .show();
                    }

                    @Override public void onError(String message) {
                        if (!isAdded()) return;
                        hideFwProgress();
                        btnFwPick.setEnabled(true);
                        btnFwUpload.setEnabled(true);
                        setFwStatus("Upload failed: " + message, true);
                    }
                });
    }

    private void setFwStatus(String text, boolean error) {
        fwStatus.setText(text);
        fwStatus.setTextColor(requireContext().getColor(
                error ? R.color.radar_red : R.color.radar_green));
    }

    /** Show the big percent + phase + progress bar together (during OTA). */
    private void showFwProgress(String phase, int percent) {
        fwProgress.setVisibility(View.VISIBLE);
        fwProgressPercent.setVisibility(View.VISIBLE);
        fwProgressPhase.setVisibility(View.VISIBLE);
        fwProgress.setProgress(percent);
        fwProgressPercent.setText(percent + "%");
        if (phase != null) fwProgressPhase.setText(phase);
    }

    /** Hide the progress widgets. Status text stays. */
    private void hideFwProgress() {
        fwProgress.setVisibility(View.GONE);
        fwProgressPercent.setVisibility(View.GONE);
        fwProgressPhase.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------
    //  CHECK FIRMWARE UPDATE — fetch manifest, compare, install
    // ------------------------------------------------------------------
    private void onCheckFirmwareUpdate() {
        String typedIp = getText(alertDeviceIp);
        if (!typedIp.isEmpty()) {
            doCheckFirmwareUpdate(typedIp, "(manual)");
            return;
        }
        DevicePickerDialog.show(requireContext(), "Check update on which ESP32?", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                // Older firmware doesn't publish /info — offer manual / AP IP / mDNS.
                offerManualIpFallback(p.name == null ? "device" : p.name, ip -> {
                    alertDeviceIp.setText(ip);
                    doCheckFirmwareUpdate(ip, p.name);
                });
                return;
            }
            alertDeviceIp.setText(p.espHttpIp);
            doCheckFirmwareUpdate(p.espHttpIp, p.name);
        });
    }

    private void doCheckFirmwareUpdate(String ip, String label) {
        btnFwCheck.setEnabled(false);
        btnFwCheck.setText("Checking…");
        setFwStatus("Fetching firmware manifest…", false);
        // Beta channel switches the manifest URL — release.yml uses the
        // /releases/latest/download/firmware.json redirect for stable; betas
        // live under a separate path the user can override later.
        boolean beta = requireContext().getSharedPreferences(FW_SKIP_PREFS, 0)
                .getBoolean("beta", false);
        String manifestUrl = beta
                ? com.example.radarhumanapplication.update.FirmwareUpdater
                        .DEFAULT_MANIFEST_URL.replace(
                                "/releases/latest/download/firmware.json",
                                "/releases/download/beta/firmware.json")
                : com.example.radarhumanapplication.update.FirmwareUpdater.DEFAULT_MANIFEST_URL;

        firmwareUpdater.check(manifestUrl, ip,
                (manifest, deviceVersion, error) -> {
                    if (!isAdded()) return;
                    btnFwCheck.setEnabled(true);
                    btnFwCheck.setText("CHECK FIRMWARE UPDATE");
                    if (manifest == null) {
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Check failed")
                                .setMessage(error != null ? error : "Could not fetch manifest")
                                .setPositiveButton("OK", null)
                                .show();
                        return;
                    }
                    lastCheckedManifest = manifest;
                    String skipped = requireContext()
                            .getSharedPreferences(FW_SKIP_PREFS, 0)
                            .getString("skip_fw_version", "");
                    boolean userSkipped = !skipped.isEmpty()
                            && skipped.equalsIgnoreCase(manifest.versionName);

                    String devLine = deviceVersion.isEmpty()
                            ? "Device (" + label + " @ " + ip + "): unknown (device offline?)"
                            : "Device (" + label + " @ " + ip + "): v" + deviceVersion;
                    String latestLine = "Latest: v" + manifest.versionName
                            + "  (" + (manifest.sizeBytes / 1024) + " KB)"
                            + (beta ? "  [BETA]" : "")
                            + (userSkipped ? "  (SKIPPED)" : "");
                    String body = devLine + "\n" + latestLine
                            + "\n\n" + (manifest.releaseNotes == null ? "" : manifest.releaseNotes);

                    boolean sameVersion = !deviceVersion.isEmpty()
                            && manifest.versionName.equalsIgnoreCase(deviceVersion);

                    androidx.appcompat.app.AlertDialog.Builder b =
                            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle(sameVersion ? "Already up to date" : "Firmware update available")
                                    .setMessage(body)
                                    .setNegativeButton("Close", null);
                    if (!sameVersion) {
                        b.setPositiveButton("Install", (d, w) -> startFirmwareInstall(manifest, ip, label));
                        b.setNeutralButton("Skip this version", (d, w) -> onSkipFirmwareVersion());
                    }
                    b.show();
                });
    }

    private void onSkipFirmwareVersion() {
        if (lastCheckedManifest == null || lastCheckedManifest.versionName == null
                || lastCheckedManifest.versionName.isEmpty()) {
            setFwStatus("Run CHECK FIRMWARE UPDATE first so we know which version to skip",
                    true);
            return;
        }
        requireContext().getSharedPreferences(FW_SKIP_PREFS, 0)
                .edit()
                .putString("skip_fw_version", lastCheckedManifest.versionName)
                .apply();
        setFwStatus("Skipped firmware v" + lastCheckedManifest.versionName
                + " — future auto-checks will ignore it", false);
    }

    private void onRollbackFirmware() {
        String typedIp = getText(alertDeviceIp);
        if (!typedIp.isEmpty()) {
            confirmRollback(typedIp, "(manual)");
            return;
        }
        DevicePickerDialog.show(requireContext(), "Rollback which ESP32?", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                offerManualIpFallback(p.name == null ? "device" : p.name, ip -> {
                    alertDeviceIp.setText(ip);
                    confirmRollback(ip, p.name);
                });
                return;
            }
            alertDeviceIp.setText(p.espHttpIp);
            confirmRollback(p.espHttpIp, p.name);
        });
    }

    private void confirmRollback(String ip, String label) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Rollback firmware?")
                .setMessage("Boot ESP32 at " + ip + " (" + label + ") into the previous"
                        + " firmware partition?\n\nThe device will reboot. If the previous"
                        + " partition is empty the rollback is a no-op.")
                .setPositiveButton("Rollback", (d, w) -> doRollback(ip))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doRollback(String ip) {
        setFwStatus("Sending rollback to " + ip + "…", false);
        alertHttp.rollbackFirmware(ip, (ok, err) -> {
            if (!isAdded()) return;
            if (Boolean.TRUE.equals(ok)) {
                setFwStatus("Rollback sent — device rebooting into previous firmware",
                        false);
            } else {
                setFwStatus("Rollback failed: " + err, true);
            }
        });
    }

    private void startFirmwareInstall(
            com.example.radarhumanapplication.update.FirmwareManifest manifest,
            String ip, String label) {
        btnFwCheck.setEnabled(false);
        btnFwPick.setEnabled(false);
        btnFwUpload.setEnabled(false);
        showFwProgress("Probing " + ip, 0);
        setFwStatus("Fetching device info from " + ip + "…", false);

        final com.example.radarhumanapplication.update.FirmwareUpdater updater = firmwareUpdater;
        // Pre-flight: fetch rich /api/info so the integrity panel can show
        // free partition size, sketch MD5, heap, etc. /api/info is newer
        // than /api/version — if it 404s, fall back to /api/version probe.
        updater.fetchDeviceInfo(ip, (info, err) -> {
            if (!isAdded()) return;
            if (info != null) {
                showFirmwareIntegrityPanel(updater, manifest, ip, label, info);
                return;
            }
            // /api/info not available — fall back to bare reachability probe.
            updater.probeReachability(ip, currentFw -> {
                if (!isAdded()) return;
                if (currentFw == null) {
                    offerMqttFallbackOrCancel(manifest, ip, label);
                    return;
                }
                // Reachable but old firmware. Synthesize a partial DeviceInfo
                // so the integrity panel still appears (without partition info).
                com.example.radarhumanapplication.update.FirmwareUpdater.DeviceInfo partial =
                        new com.example.radarhumanapplication.update.FirmwareUpdater
                                .DeviceInfo(currentFw, ip, "", label,
                                        0L, 0L, 0L, "", 0L, 0L);
                showFirmwareIntegrityPanel(updater, manifest, ip, label, partial);
            });
        });
    }

    /**
     * Pre-upload integrity panel. Shows the .bin's expected SHA, the file
     * size + ETA, the device's reported free OTA partition, and warns up-
     * front if the .bin is too big to fit — instead of letting the upload
     * complete and then silently rolling back to the previous partition.
     *
     * <p>If the device's current firmware supports {@code ota_pull}
     * (v1.0.48+), this also auto-suggests using MQTT-pull as the default
     * transport since it's more reliable than the HTTP-push path.
     */
    private void showFirmwareIntegrityPanel(
            final com.example.radarhumanapplication.update.FirmwareUpdater updater,
            final com.example.radarhumanapplication.update.FirmwareManifest manifest,
            final String ip, final String label,
            final com.example.radarhumanapplication.update.FirmwareUpdater.DeviceInfo info) {
        if (!isAdded()) return;
        hideFwProgress();
        btnFwCheck.setEnabled(true);
        btnFwPick.setEnabled(true);
        btnFwUpload.setEnabled(pickedFirmwareUri != null);

        long sizeBytes = manifest.sizeBytes > 0 ? manifest.sizeBytes : 0;
        boolean partitionKnown = info.freeAppPartitionBytes > 0;
        boolean fits = !partitionKnown || sizeBytes == 0
                || sizeBytes <= info.freeAppPartitionBytes;
        long etaSec = sizeBytes > 0 ? (sizeBytes / 150_000L) + 2 : 0; // ~150 KB/s rough est
        boolean supportsMqttPull = com.example.radarhumanapplication.update.FirmwareUpdater
                .compareFwVersion(info.fw, "1.0.48") >= 0;

        StringBuilder body = new StringBuilder();
        body.append("Target:   ").append(label.isEmpty() ? "(device)" : label)
                .append("  @ ").append(ip).append('\n');
        body.append("Current:  v").append(info.fw.isEmpty() ? "?" : info.fw).append('\n');
        body.append("New:      v").append(manifest.versionName)
                .append("  (").append(formatBytes(sizeBytes)).append(")\n");
        if (etaSec > 0) {
            body.append("ETA:      ~").append(etaSec).append("s over HTTP push\n");
        }
        body.append('\n');
        if (manifest.sha256 != null && !manifest.sha256.isEmpty()) {
            body.append("SHA-256:  ").append(shortHash(manifest.sha256)).append('\n');
        }
        if (!info.sketchMd5.isEmpty()) {
            body.append("Running MD5: ").append(shortHash(info.sketchMd5)).append('\n');
        }
        if (partitionKnown) {
            body.append("Free OTA slot: ").append(formatBytes(info.freeAppPartitionBytes))
                    .append(fits ? "   ✅ fits" : "   ❌ TOO SMALL").append('\n');
        }
        if (info.totalHeap > 0) {
            body.append("Device heap:   ")
                    .append(info.freeHeap / 1024).append(" / ")
                    .append(info.totalHeap / 1024).append(" KB\n");
        }
        body.append('\n');
        if (!fits) {
            body.append("⚠ The new firmware won't fit in this device's OTA slot.\n")
                    .append("   Flash a smaller build, or reflash via USB.");
        } else if (supportsMqttPull) {
            body.append("✓ Device supports MQTT-pull (more reliable than HTTP push).");
        } else {
            body.append("ℹ This firmware predates MQTT-pull. HTTP push is the only option.");
        }

        androidx.appcompat.app.AlertDialog.Builder b =
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Confirm firmware install")
                        .setMessage(body.toString())
                        .setNegativeButton("Cancel", null);
        if (fits) {
            String httpLabel = supportsMqttPull ? "HTTP push (legacy)" : "Install (HTTP)";
            String mqttLabel = "Install via MQTT";
            if (supportsMqttPull && mqtt.isConnected()) {
                b.setPositiveButton(mqttLabel,
                        (d, w) -> startMqttFirmwareInstall(manifest, label));
                b.setNeutralButton(httpLabel,
                        (d, w) -> doHttpFirmwareInstall(updater, manifest, ip, label));
            } else {
                b.setPositiveButton("Install (HTTP)",
                        (d, w) -> doHttpFirmwareInstall(updater, manifest, ip, label));
                if (mqtt.isConnected()) {
                    b.setNeutralButton(mqttLabel,
                            (d, w) -> startMqttFirmwareInstall(manifest, label));
                }
            }
        }
        b.show();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US,
                "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0);
    }
    private static String shortHash(String h) {
        if (h == null || h.length() < 12) return h == null ? "" : h;
        return h.substring(0, 8) + "…" + h.substring(h.length() - 4);
    }

    /** Dialog shown after we detect the device rolled back to its previous
     *  firmware (verify came back with the OLD fw, not the manifest's). Offers
     *  three escape hatches: retry HTTP, switch to MQTT-pull, USB flash guide. */
    private void showRollbackRecoveryDialog(
            final com.example.radarhumanapplication.update.FirmwareManifest manifest,
            final String ip, final String label, final String actualFw) {
        if (!isAdded()) return;
        boolean supportsMqttPull = com.example.radarhumanapplication.update.FirmwareUpdater
                .compareFwVersion(actualFw, "1.0.48") >= 0;
        String body = "Device rebooted but came back running v" + actualFw
                + " — the new firmware v" + manifest.versionName + " was "
                + "rejected and the chip rolled back.\n\n"
                + "Best fix: install the smaller BRIDGE firmware first. It's "
                + "OTA-only (no radar/web/alerts) but ~15% smaller so it "
                + "usually uploads in one shot over noisy WiFi. Once the "
                + "bridge is running, it pulls the full firmware directly "
                + "from GitHub via MQTT — bypassing the WiFi upload problem "
                + "entirely.\n\n"
                + "All your NVS settings (WiFi, MQTT, alerts, zones) are "
                + "preserved across both steps.";
        androidx.appcompat.app.AlertDialog.Builder b =
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("⚠ OTA rejected — chip rolled back")
                        .setMessage(body)
                        .setPositiveButton("Install bridge firmware",
                                (d, w) -> startBridgeFirmwareInstall(ip, label));
        if (supportsMqttPull && mqtt.isConnected()) {
            b.setNeutralButton("Retry via MQTT",
                    (d, w) -> startMqttFirmwareInstall(manifest, label));
        } else {
            b.setNeutralButton("Retry HTTP",
                    (d, w) -> doHttpFirmwareInstall(firmwareUpdater, manifest, ip, label));
        }
        b.setNegativeButton("USB guide", (d, w) -> showUsbFlashGuide());
        b.show();
    }

    /**
     * Fetch the bridge manifest from the latest release, then HTTP-push the
     * smaller bridge .bin to the device. On success the bridge boots,
     * reconnects MQTT, and the user can then issue ota_pull to fetch the
     * full firmware from GitHub directly.
     */
    private void startBridgeFirmwareInstall(final String ip, final String label) {
        if (!isAdded()) return;
        btnFwCheck.setEnabled(false);
        btnFwPick.setEnabled(false);
        btnFwUpload.setEnabled(false);
        showFwProgress("Fetching bridge manifest", 0);
        setFwStatus("Looking up bridge firmware…", false);

        final com.example.radarhumanapplication.update.FirmwareUpdater updater = firmwareUpdater;
        // bridge.json lives next to firmware.json in the same GitHub Release.
        // Replacing the filename in DEFAULT_MANIFEST_URL keeps the path
        // logic in one place even if we move releases later.
        String bridgeManifestUrl = com.example.radarhumanapplication.update.FirmwareUpdater
                .DEFAULT_MANIFEST_URL.replace("firmware.json", "bridge.json");
        updater.check(bridgeManifestUrl, null, (manifest, deviceVersion, error) -> {
            if (!isAdded()) return;
            if (manifest == null) {
                hideFwProgress();
                btnFwCheck.setEnabled(true);
                btnFwPick.setEnabled(true);
                btnFwUpload.setEnabled(pickedFirmwareUri != null);
                setFwStatus("Bridge manifest unreachable: " + error, true);
                return;
            }
            doHttpFirmwareInstall(updater, manifest, ip, label + " (bridge)");
        });
    }

    private void showUsbFlashGuide() {
        if (!isAdded()) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("USB flash guide")
                .setMessage("If OTA keeps rolling back, flash the firmware over USB once "
                        + "to bridge to a known-good baseline (v1.0.48+ supports the "
                        + "more reliable MQTT-pull OTA):\n\n"
                        + "1. Plug ESP32 into your computer via USB.\n"
                        + "2. From the project root run:\n"
                        + "     pio run -d firmware -t upload --upload-port COM5\n"
                        + "   (replace COM5 with the actual port from\n"
                        + "    Device Manager / `pio device list`)\n\n"
                        + "3. If 'wrong boot mode' appears: unplug USB, hold the\n"
                        + "    BOOT button on the ESP32, plug USB back in, release\n"
                        + "    the button, retry upload.\n\n"
                        + "4. After it boots into the new firmware, all future\n"
                        + "    updates can use MQTT-pull from this app — no USB\n"
                        + "    cable needed.")
                .setPositiveButton("OK", null)
                .show();
    }

    /** Dialog: "ESP32 isn't reachable on HTTP — install via MQTT instead?"
     *  Offers the MQTT-pull path which works as long as both the phone and
     *  the device can reach the broker. */
    private void offerMqttFallbackOrCancel(
            com.example.radarhumanapplication.update.FirmwareManifest manifest,
            String ip, String label) {
        hideFwProgress();
        btnFwCheck.setEnabled(true);
        btnFwPick.setEnabled(true);
        btnFwUpload.setEnabled(pickedFirmwareUri != null);
        setFwStatus("HTTP to " + ip + " unreachable", true);

        boolean canMqtt = mqtt.isConnected()
                && mqtt.getDeviceName() != null
                && !mqtt.getDeviceName().isEmpty();
        androidx.appcompat.app.AlertDialog.Builder b =
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("ESP32 not reachable")
                        .setMessage("Cannot reach " + label + " at " + ip
                                + " over HTTP. Likely causes:\n"
                                + "  • phone on a different WiFi network\n"
                                + "  • ESP32 powered off or in AP mode\n"
                                + "  • firewall blocking port 80\n\n"
                                + (canMqtt
                                        ? "Want to install via MQTT instead? "
                                                + "The ESP32 will download the firmware "
                                                + "itself from GitHub Releases."
                                        : "Connect to MQTT first to use the "
                                                + "MQTT-pull fallback."))
                        .setNegativeButton("Cancel", null);
        if (canMqtt) {
            b.setPositiveButton("Install via MQTT",
                    (d, w) -> startMqttFirmwareInstall(manifest, label));
        }
        b.show();
    }

    /** MQTT-pull install: publish ota_pull cmd, then verify via MQTT. */
    private void startMqttFirmwareInstall(
            com.example.radarhumanapplication.update.FirmwareManifest manifest,
            String label) {
        btnFwCheck.setEnabled(false);
        btnFwPick.setEnabled(false);
        btnFwUpload.setEnabled(false);
        showFwProgress("Sending ota_pull via MQTT", 50);
        setFwStatus("ESP32 will download from " + manifest.binUrl + "…", false);

        final String expectedName = mqtt.getDeviceName();
        final com.example.radarhumanapplication.update.FirmwareUpdater updater = firmwareUpdater;
        updater.installViaMqtt(manifest, expectedName,
                new com.example.radarhumanapplication.update.FirmwareUpdater.InstallCallback() {
                    @Override public void onProgress(int p, String phase) {
                        if (!isAdded()) return;
                        showFwProgress(phase, p);
                    }
                    @Override public void onUploaded() {
                        if (!isAdded()) return;
                        showFwProgress("Waiting for ESP32 to flash + reboot", 100);
                        setFwStatus("ESP32 is downloading and flashing — this "
                                + "can take 60-120s. Watch GPIO26 for the OTA "
                                + "heartbeat (1 pulse / 1.5s).", false);
                        // Bigger timeout for MQTT-pull — ESP has to download
                        // + flash from scratch, not just receive bytes.
                        updater.verifyOnMqtt(
                                expectedName, manifest.versionName,
                                /*deviceIpHint=*/ null,
                                180_000, this);
                    }
                    @Override public void onInstalled(
                            com.example.radarhumanapplication.update.FirmwareUpdater
                                    .MqttVerifyResult info) {
                        if (!isAdded()) return;
                        hideFwProgress();
                        btnFwCheck.setEnabled(true);
                        btnFwPick.setEnabled(true);
                        btnFwUpload.setEnabled(pickedFirmwareUri != null);
                        setFwStatus("ESP32 (" + label + ") now running v"
                                + (info.fw.isEmpty() ? manifest.versionName : info.fw),
                                false);
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("MQTT OTA installed")
                                .setMessage("Device:    " + info.deviceName
                                        + "\nFirmware:  v" + (info.fw.isEmpty()
                                                ? manifest.versionName : info.fw)
                                        + (info.ip.isEmpty() ? "" : "\nIP:        " + info.ip)
                                        + "\n\n✅ Verified via MQTT.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    @Override public void onError(String message) {
                        if (!isAdded()) return;
                        hideFwProgress();
                        btnFwCheck.setEnabled(true);
                        btnFwPick.setEnabled(true);
                        btnFwUpload.setEnabled(pickedFirmwareUri != null);
                        setFwStatus("MQTT OTA failed: " + message, true);
                    }
                });
    }

    private void doHttpFirmwareInstall(
            final com.example.radarhumanapplication.update.FirmwareUpdater updater,
            final com.example.radarhumanapplication.update.FirmwareManifest manifest,
            final String ip, final String label) {
        showFwProgress("Starting", 0);
        setFwStatus("Starting…", false);
        updater.install(manifest, ip,
                new com.example.radarhumanapplication.update.FirmwareUpdater.InstallCallback() {
                    @Override public void onProgress(int percent, String phase) {
                        if (!isAdded()) return;
                        showFwProgress(phase + " (" + label + ")", percent);
                        setFwStatus(phase + "… " + percent + "%", false);
                    }
                    @Override public void onUploaded() {
                        if (!isAdded()) return;
                        // Bytes are on the device; ESP32 is finishing the
                        // 4× 250 ms blink and about to ESP.restart(). Switch
                        // to MQTT-based verification — the new firmware
                        // republishes /info with the new fw field on reconnect.
                        showFwProgress("Verifying via MQTT", 100);
                        setFwStatus("Uploaded — waiting for ESP32 to come back "
                                + "online on MQTT…", false);
                        // Active device name from MqttService is the most
                        // reliable match for the just-flashed device.
                        String expectedName = mqtt.getDeviceName();
                        if (expectedName == null || expectedName.isEmpty()) {
                            expectedName = label;
                        }
                        // Pass the device IP we just uploaded to — verify
                        // will HTTP-poll /api/version in parallel with the
                        // MQTT /info wait so a stuck broker doesn't strand
                        // the user on "Timeout".
                        updater.verifyOnMqtt(
                                expectedName,
                                manifest.versionName,
                                ip,
                                com.example.radarhumanapplication.update.FirmwareUpdater
                                        .DEFAULT_MQTT_VERIFY_TIMEOUT_MS,
                                this);
                    }
                    @Override public void onInstalled(
                            com.example.radarhumanapplication.update.FirmwareUpdater
                                    .MqttVerifyResult info) {
                        if (!isAdded()) return;
                        showFwProgress("Installed v" + manifest.versionName, 100);
                        btnFwCheck.setEnabled(true);
                        btnFwPick.setEnabled(true);
                        btnFwUpload.setEnabled(pickedFirmwareUri != null);
                        setFwStatus("ESP32 (" + label + ") now running v"
                                + (info.fw.isEmpty()
                                        ? manifest.versionName : info.fw),
                                false);
                        StringBuilder body = new StringBuilder();
                        body.append("Device:   ").append(info.deviceName).append('\n');
                        body.append("Firmware: v").append(info.fw.isEmpty()
                                ? manifest.versionName : info.fw).append('\n');
                        if (!info.ip.isEmpty())  body.append("IP:       ").append(info.ip).append('\n');
                        if (!info.mac.isEmpty()) body.append("MAC:      ").append(info.mac).append('\n');
                        body.append('\n').append(info.versionMatches
                                ? "✅ Version match confirmed via MQTT."
                                : "⚠ Device returned but the reported version "
                                        + "differs from the manifest.");
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Firmware installed")
                                .setMessage(body.toString())
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    @Override public void onError(String message) {
                        if (!isAdded()) return;
                        hideFwProgress();
                        btnFwCheck.setEnabled(true);
                        btnFwPick.setEnabled(true);
                        btnFwUpload.setEnabled(pickedFirmwareUri != null);
                        setFwStatus("Install failed: " + message, true);
                        // FirmwareUpdater.verifyOnMqtt() tags rollback timeouts
                        // with this exact phrase — pop the recovery dialog so
                        // the user has next-step actions instead of dead-ending
                        // on a red status line.
                        String actualFw = extractRolledBackFw(message);
                        if (actualFw != null) {
                            showRollbackRecoveryDialog(manifest, ip, label, actualFw);
                        }
                    }
                });
    }

    /** Pull "fw=1.0.25" out of the verifyOnMqtt timeout message. */
    private static String extractRolledBackFw(String message) {
        if (message == null) return null;
        int idx = message.indexOf("came back with fw=");
        if (idx < 0) return null;
        int start = idx + "came back with fw=".length();
        int end = start;
        while (end < message.length()) {
            char c = message.charAt(end);
            if (Character.isDigit(c) || c == '.') end++;
            else break;
        }
        return end > start ? message.substring(start, end) : null;
    }

    // ------------------------------------------------------------------
    //  Device online/offline notification card
    // ------------------------------------------------------------------
    private void bindDeviceStatusAlertUi(View v) {
        devDeviceLabel      = v.findViewById(R.id.dev_alert_device_label);
        devOnlineEnable     = v.findViewById(R.id.dev_alert_online_enable);
        devOfflineEnable    = v.findViewById(R.id.dev_alert_offline_enable);
        devOnlineUriLabel   = v.findViewById(R.id.dev_alert_online_uri_label);
        devOfflineUriLabel  = v.findViewById(R.id.dev_alert_offline_uri_label);
        btnPickOnline       = v.findViewById(R.id.dev_alert_pick_online);
        btnPickOffline      = v.findViewById(R.id.dev_alert_pick_offline);
        btnTestOnline       = v.findViewById(R.id.dev_alert_test_online);
        btnTestOffline      = v.findViewById(R.id.dev_alert_test_offline);
        devAlertStatus      = v.findViewById(R.id.dev_alert_status);

        loadCurrentDeviceStatusAlertCfg();

        btnPickOnline.setOnClickListener(view -> launchDeviceSoundPicker(true));
        btnPickOffline.setOnClickListener(view -> launchDeviceSoundPicker(false));
        btnTestOnline.setOnClickListener(view -> testDeviceSound(true));
        btnTestOffline.setOnClickListener(view -> testDeviceSound(false));

        // Tap the "Configuring: …" label to switch which device's online/offline
        // sounds are being edited. Works for multi-device installs.
        devDeviceLabel.setOnClickListener(view -> pickEditingDevice());

        devOnlineEnable.setOnCheckedChangeListener((b, checked) -> saveCurrentDeviceStatusAlertCfg());
        devOfflineEnable.setOnCheckedChangeListener((b, checked) -> saveCurrentDeviceStatusAlertCfg());
    }

    private void pickEditingDevice() {
        DevicePickerDialog.show(requireContext(), "Configure which device?", p -> {
            String name = p.deviceName == null ? "" : p.deviceName;
            if (name.isEmpty()) return;
            devAlertEditingDevice = name;
            loadCurrentDeviceStatusAlertCfg();
            setDevAlertStatus("Editing config for device: " + name, false);
        });
    }

    /** Device name we're editing — defaults to the active MQTT device when nothing was picked. */
    private String currentEditingDevice() {
        if (devAlertEditingDevice != null && !devAlertEditingDevice.isEmpty()) {
            return devAlertEditingDevice;
        }
        return mqtt.getDeviceName();
    }

    private void loadCurrentDeviceStatusAlertCfg() {
        DeviceStatusAlertManager mgr = mqtt.getDeviceStatusAlerts();
        String dev = currentEditingDevice();
        String hint = "Configuring: " + (dev == null || dev.isEmpty() ? "(tap to pick)" : dev)
                    + "  (tap to change)";
        devDeviceLabel.setText(hint);
        if (mgr == null || dev == null || dev.isEmpty()) {
            devOnlineUri = "";
            devOfflineUri = "";
            devOnlineUriLabel.setText("Online sound: (none)");
            devOfflineUriLabel.setText("Offline sound: (none)");
            return;
        }
        DeviceStatusAlertConfig c = mgr.getOrCreate(dev);
        devOnlineEnable.setChecked(c.onlineEnabled);
        devOfflineEnable.setChecked(c.offlineEnabled);
        devOnlineUri  = c.onlineSoundUri  == null ? "" : c.onlineSoundUri;
        devOfflineUri = c.offlineSoundUri == null ? "" : c.offlineSoundUri;
        devOnlineUriLabel.setText("Online sound: "
                + (devOnlineUri.isEmpty()  ? "(none)" : lastSegment(devOnlineUri)));
        devOfflineUriLabel.setText("Offline sound: "
                + (devOfflineUri.isEmpty() ? "(none)" : lastSegment(devOfflineUri)));
    }

    private void saveCurrentDeviceStatusAlertCfg() {
        DeviceStatusAlertManager mgr = mqtt.getDeviceStatusAlerts();
        String dev = currentEditingDevice();
        if (mgr == null || dev == null || dev.isEmpty()) return;
        DeviceStatusAlertConfig c = new DeviceStatusAlertConfig(dev);
        c.onlineEnabled   = devOnlineEnable.isChecked();
        c.offlineEnabled  = devOfflineEnable.isChecked();
        c.onlineSoundUri  = devOnlineUri;
        c.offlineSoundUri = devOfflineUri;
        mgr.save(c);
    }

    private void launchDeviceSoundPicker(boolean online) {
        String[] mimes = {"audio/*"};
        try {
            (online ? pickOnlineLauncher : pickOfflineLauncher).launch(mimes);
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "No file picker available: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onDeviceSoundPicked(@Nullable Uri uri, boolean online) {
        if (uri == null) return;
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {}
        String s = uri.toString();
        if (online) {
            devOnlineUri = s;
            devOnlineUriLabel.setText("Online sound: " + lastSegment(s));
        } else {
            devOfflineUri = s;
            devOfflineUriLabel.setText("Offline sound: " + lastSegment(s));
        }
        saveCurrentDeviceStatusAlertCfg();
        setDevAlertStatus("Saved " + (online ? "online" : "offline") + " sound", false);
    }

    private void testDeviceSound(boolean online) {
        DeviceStatusAlertManager mgr = mqtt.getDeviceStatusAlerts();
        String dev = currentEditingDevice();
        if (mgr == null || dev == null || dev.isEmpty()) {
            setDevAlertStatus("No device configured", true);
            return;
        }
        // Make sure the manager has the latest from this UI before testing
        saveCurrentDeviceStatusAlertCfg();
        mgr.testPlay(dev, online);
        setDevAlertStatus("Playing " + (online ? "online" : "offline")
                + " sound for " + dev, false);
    }

    private void setDevAlertStatus(String text, boolean error) {
        devAlertStatus.setText(text);
        devAlertStatus.setTextColor(requireContext().getColor(
                error ? R.color.radar_red : R.color.radar_green));
    }

    // ------------------------------------------------------------------
    //  Alert / GPIO26 buzzer card
    // ------------------------------------------------------------------
    private void bindAlertUi(View v) {
        alertEnable      = v.findViewById(R.id.alert_enable);
        alertLedWifi     = v.findViewById(R.id.alert_led_wifi);
        alertShortMs     = v.findViewById(R.id.alert_short_ms);
        alertLongMs      = v.findViewById(R.id.alert_long_ms);
        alertGapMs       = v.findViewById(R.id.alert_gap_ms);
        alertDebounceMs  = v.findViewById(R.id.alert_debounce_ms);
        alertMaxRange    = v.findViewById(R.id.alert_max_range);
        alertDeviceIp    = v.findViewById(R.id.alert_device_ip);
        alertSoundType   = v.findViewById(R.id.alert_sound_type);
        alertSoundTone     = v.findViewById(R.id.alert_sound_tone);
        alertSoundWav      = v.findViewById(R.id.alert_sound_wav);
        alertSoundTts      = v.findViewById(R.id.alert_sound_tts);
        alertVolume        = v.findViewById(R.id.alert_volume);
        alertRoutingNotif  = v.findViewById(R.id.alert_routing_notification);
        alertRoutingAlarm  = v.findViewById(R.id.alert_routing_alarm);
        alertVolumeLabel = v.findViewById(R.id.alert_volume_label);
        alertStatus      = v.findViewById(R.id.alert_status);
        btnAlertFetch       = v.findViewById(R.id.btn_alert_fetch);
        btnAlertPush        = v.findViewById(R.id.btn_alert_push);
        btnAlertTestLocal   = v.findViewById(R.id.btn_alert_test_local);
        btnAlertTestRemote  = v.findViewById(R.id.btn_alert_test_remote);
        btnPickShort        = v.findViewById(R.id.btn_pick_short);
        btnPickLong         = v.findViewById(R.id.btn_pick_long);
        btnPickDeviceIp     = v.findViewById(R.id.btn_pick_device_ip);
        alertShortUriLabel  = v.findViewById(R.id.alert_short_uri_label);
        alertLongUriLabel   = v.findViewById(R.id.alert_long_uri_label);

        AlertPatternConfig cfg = loadAlertPrefs();
        applyAlertCfgToUi(cfg);
        mqtt.updateAlertConfig(cfg);

        alertVolume.addOnChangeListener((slider, value, fromUser) ->
                alertVolumeLabel.setText("Mobile volume: " + (int) value + "%"));

        btnAlertFetch.setOnClickListener(view -> onAlertFetch());
        btnAlertPush.setOnClickListener(view -> onAlertPush());
        btnAlertTestLocal.setOnClickListener(view -> onAlertTestLocal());
        btnAlertTestRemote.setOnClickListener(view -> onAlertTestRemote());
        btnPickShort.setOnClickListener(view -> launchPicker(true));
        btnPickLong.setOnClickListener(view -> launchPicker(false));
        btnPickDeviceIp.setOnClickListener(view -> onPickDeviceIp());
    }

    /** Show the picker; fill the IP field from the chosen profile / MQTT discovery. */
    private void onPickDeviceIp() {
        DevicePickerDialog.show(requireContext(), "Pick a device for the IP field", p -> {
            if (p.espHttpIp != null && !p.espHttpIp.isEmpty()) {
                alertDeviceIp.setText(p.espHttpIp);
                setAlertStatus("IP set to " + p.espHttpIp + " (" + p.name + ")", false);
            } else {
                offerManualIpFallback(p.name == null ? "device" : p.name,
                        ip -> {
                            alertDeviceIp.setText(ip);
                            setAlertStatus("IP set to " + ip, false);
                        });
            }
        });
    }

    /**
     * Shown when a picked device has no IP available — typically because the ESP32 still
     * runs firmware older than v1.0.23 (no humanradar/<name>/info publish), so the only
     * thing the app knows is "device is online on MQTT". Offer a few quick guesses plus
     * a manual entry path.
     */
    private interface IpChoice { void onIpChosen(String ip); }

    private void offerManualIpFallback(String label, IpChoice cb) {
        final String mdns = "HumanRadar.local";
        final String ap   = "192.168.4.1";
        String[] options = {
                "Use AP IP (" + ap + ")",
                "Use mDNS (" + mdns + ")",
                "Enter IP manually",
        };
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("\"" + label + "\" did not announce an IP")
                .setMessage("ESP32 publishes its IP automatically only when it runs "
                        + "firmware v1.0.23 or newer.\n\n"
                        + "If your device runs older firmware, pick one of these:")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        cb.onIpChosen(ap);
                    } else if (which == 1) {
                        cb.onIpChosen(mdns);
                    } else {
                        // Focus the IP field so the user can just start typing.
                        alertDeviceIp.requestFocus();
                        setAlertStatus("Type the device IP (find it in /settings page or "
                                + "Arduino IDE Serial Monitor)", false);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void launchPicker(boolean isShort) {
        String[] mimes = {"audio/*"};
        try {
            (isShort ? pickShortLauncher : pickLongLauncher).launch(mimes);
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "No file picker available: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onSoundPicked(@Nullable Uri uri, boolean isShort) {
        if (uri == null) return;
        // Persist read permission across reboots so MediaPlayer can open it later
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            requireContext().getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
            // Some providers don't support persistable grants; we'll still try to play it.
        }
        String s = uri.toString();
        if (isShort) {
            pickedShortUri = s;
            alertShortUriLabel.setText("Short beep sound: " + lastSegment(s));
        } else {
            pickedLongUri = s;
            alertLongUriLabel.setText("Long beep sound: " + lastSegment(s));
        }
        // Force WAV mode + push new config so the player reloads MediaPlayer with the new URI
        alertSoundWav.setChecked(true);
        AlertPatternConfig c = readAlertCfgFromUi();
        saveAlertPrefs(c);
        mqtt.updateAlertConfig(c);
        setAlertStatus("Sound saved (" + (isShort ? "short" : "long") + ")", false);
    }

    private static String lastSegment(String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return "(none)";
        int slash = uriStr.lastIndexOf('/');
        if (slash < 0 || slash == uriStr.length() - 1) return uriStr;
        String tail = uriStr.substring(slash + 1);
        // Strip any document id prefix
        int colon = tail.lastIndexOf(':');
        return colon >= 0 ? tail.substring(colon + 1) : tail;
    }

    private void applyAlertCfgToUi(AlertPatternConfig c) {
        alertEnable.setChecked(c.alertEnabled);
        alertLedWifi.setChecked(c.ledWifiEnabled);
        alertShortMs.setText(String.valueOf(c.beepShortMs));
        alertLongMs.setText(String.valueOf(c.beepLongMs));
        alertGapMs.setText(String.valueOf(c.beepGapMs));
        alertDebounceMs.setText(String.valueOf(c.debounceMs));
        alertMaxRange.setText(String.valueOf(c.maxRangeMm));
        alertVolume.setValue(Math.max(0, Math.min(100, c.volumePct)));
        alertVolumeLabel.setText("Mobile volume: " + c.volumePct + "%");
        switch (c.soundType) {
            case WAV:  alertSoundWav.setChecked(true); break;
            case TTS:  alertSoundTts.setChecked(true); break;
            case TONE: default: alertSoundTone.setChecked(true); break;
        }
        if (c.routing == AlertPatternConfig.Routing.ALARM) alertRoutingAlarm.setChecked(true);
        else                                               alertRoutingNotif.setChecked(true);
        pickedShortUri = c.shortSoundUri == null ? "" : c.shortSoundUri;
        pickedLongUri  = c.longSoundUri  == null ? "" : c.longSoundUri;
        alertShortUriLabel.setText("Short beep sound: "
                + (pickedShortUri.isEmpty() ? "(none)" : lastSegment(pickedShortUri)));
        alertLongUriLabel.setText("Long beep sound: "
                + (pickedLongUri.isEmpty()  ? "(none)" : lastSegment(pickedLongUri)));
    }

    private AlertPatternConfig readAlertCfgFromUi() {
        AlertPatternConfig c = new AlertPatternConfig();
        c.alertEnabled   = alertEnable.isChecked();
        c.ledWifiEnabled = alertLedWifi.isChecked();
        c.beepShortMs    = clamp(parseInt(getText(alertShortMs),    200), 50,  5000);
        c.beepLongMs     = clamp(parseInt(getText(alertLongMs),     800), 100, 10000);
        c.beepGapMs      = clamp(parseInt(getText(alertGapMs),      200), 50,  5000);
        c.debounceMs     = clamp(parseInt(getText(alertDebounceMs), 500), 0,   10000);
        c.maxRangeMm     = clamp(parseInt(getText(alertMaxRange),   6000), 0, 20000);
        c.volumePct      = (int) alertVolume.getValue();
        if (alertSoundWav.isChecked())      c.soundType = AlertPatternConfig.SoundType.WAV;
        else if (alertSoundTts.isChecked()) c.soundType = AlertPatternConfig.SoundType.TTS;
        else                                c.soundType = AlertPatternConfig.SoundType.TONE;
        c.routing = alertRoutingAlarm != null && alertRoutingAlarm.isChecked()
                ? AlertPatternConfig.Routing.ALARM
                : AlertPatternConfig.Routing.NOTIFICATION;
        c.shortSoundUri = pickedShortUri;
        c.longSoundUri  = pickedLongUri;
        return c;
    }

    private void onAlertFetch() {
        String typedIp = getText(alertDeviceIp);
        if (!typedIp.isEmpty()) {
            doAlertFetch(typedIp);
            return;
        }
        DevicePickerDialog.show(requireContext(), "Fetch from device",
                p -> {
                    if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                        offerManualIpFallback(p.name == null ? "device" : p.name, ip -> {
                            alertDeviceIp.setText(ip);
                            doAlertFetch(ip);
                        });
                        return;
                    }
                    alertDeviceIp.setText(p.espHttpIp);
                    doAlertFetch(p.espHttpIp);
                });
    }

    private void doAlertFetch(String ip) {
        setAlertStatus("Fetching from " + ip + "...", false);
        alertHttp.fetchConfig(ip, (cfg, err) -> {
            if (!isAdded()) return;
            if (err != null) { setAlertStatus("Fetch failed: " + err, true); return; }
            // Keep mobile-only fields (sound type/volume) from current UI
            AlertPatternConfig merged = readAlertCfgFromUi();
            merged.alertEnabled   = cfg.alertEnabled;
            merged.ledWifiEnabled = cfg.ledWifiEnabled;
            merged.beepShortMs    = cfg.beepShortMs;
            merged.beepLongMs     = cfg.beepLongMs;
            merged.beepGapMs      = cfg.beepGapMs;
            merged.debounceMs     = cfg.debounceMs;
            merged.maxRangeMm     = cfg.maxRangeMm;
            applyAlertCfgToUi(merged);
            saveAlertPrefs(merged);
            mqtt.updateAlertConfig(merged);
            setAlertStatus("Fetched OK", false);
        });
    }

    private void onAlertPush() {
        AlertPatternConfig c = readAlertCfgFromUi();
        saveAlertPrefs(c);
        mqtt.updateAlertConfig(c);
        String typedIp = getText(alertDeviceIp);
        if (!typedIp.isEmpty()) {
            doAlertPush(typedIp, c);
            return;
        }
        DevicePickerDialog.show(requireContext(), "Push to device", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                offerManualIpFallback(p.name == null ? "device" : p.name, ip -> {
                    alertDeviceIp.setText(ip);
                    doAlertPush(ip, c);
                });
                return;
            }
            alertDeviceIp.setText(p.espHttpIp);
            doAlertPush(p.espHttpIp, c);
        });
    }

    private void doAlertPush(String ip, AlertPatternConfig c) {
        setAlertStatus("Pushing to " + ip + "...", false);
        alertHttp.pushConfig(ip, c, (ok, err) -> {
            if (!isAdded()) return;
            if (Boolean.TRUE.equals(ok)) setAlertStatus("Pushed OK to " + ip, false);
            else setAlertStatus("Push failed: " + err, true);
        });
    }

    private void onAlertTestLocal() {
        AlertPatternConfig c = readAlertCfgFromUi();
        mqtt.updateAlertConfig(c);
        if (mqtt.getAlertPlayer() != null) {
            mqtt.getAlertPlayer().triggerTest(2, false);
            setAlertStatus("Phone test: 2 beeps", false);
        }
    }

    private void onAlertTestRemote() {
        String typedIp = getText(alertDeviceIp);
        if (!typedIp.isEmpty()) {
            doAlertTestRemote(typedIp, "(manual)");
            return;
        }
        DevicePickerDialog.show(requireContext(), "Test which ESP32?", p -> {
            if (p.espHttpIp == null || p.espHttpIp.isEmpty()) {
                offerManualIpFallback(p.name == null ? "device" : p.name, ip -> {
                    alertDeviceIp.setText(ip);
                    doAlertTestRemote(ip, p.name);
                });
                return;
            }
            alertDeviceIp.setText(p.espHttpIp);
            doAlertTestRemote(p.espHttpIp, p.name);
        });
    }

    private void doAlertTestRemote(String ip, String label) {
        setAlertStatus("Triggering ESP32 (" + label + ") at " + ip + "...", false);
        alertHttp.testBeep(ip, 2, false, (ok, err) -> {
            if (!isAdded()) return;
            if (Boolean.TRUE.equals(ok)) {
                setAlertStatus("ESP32 test sent via HTTP to " + ip, false);
                return;
            }
            // HTTP failed — common when phone is on a different LAN than the
            // ESP32 (hotspot, different SSID, etc.). Fall back to MQTT cmd
            // alert_test which works as long as both can reach the broker.
            android.util.Log.w("ConfigFragment",
                    "HTTP alert_test failed (" + err + "), trying MQTT");
            if (!mqtt.isConnected()) {
                setAlertStatus("ESP32 test failed: " + err
                        + " (MQTT also offline)", true);
                return;
            }
            String targetName = (label == null || label.isEmpty()
                    || "(manual)".equals(label))
                    ? mqtt.getDeviceName() : label;
            if (targetName == null || targetName.isEmpty()) {
                setAlertStatus("ESP32 test failed: " + err
                        + " (no device name for MQTT fallback)", true);
                return;
            }
            com.google.gson.JsonObject extras = new com.google.gson.JsonObject();
            extras.addProperty("count", 2);
            extras.addProperty("long",  0);
            // sendCommandTo wraps the payload as {request_id, cmd, ...extras}
            // and publishes to humanradar/<name>/cmd — ESP32 fw ≥ v1.0.44 maps
            // 'alert_test' to alertPattern.triggerTest(count, long).
            mqtt.sendCommandToWithExtras(targetName, "alert_test", extras);
            setAlertStatus("HTTP unreachable — sent alert_test via MQTT to "
                    + targetName + " (needs firmware ≥ v1.0.44)", false);
            return;
        });
    }

    private void setAlertStatus(String text, boolean error) {
        alertStatus.setText(text);
        alertStatus.setTextColor(requireContext().getColor(
                error ? R.color.radar_red : R.color.radar_green));
    }

    private SharedPreferences alertPrefs() {
        return requireContext().getSharedPreferences(ALERT_PREFS, 0);
    }

    private AlertPatternConfig loadAlertPrefs() {
        SharedPreferences p = alertPrefs();
        AlertPatternConfig c = new AlertPatternConfig();
        c.alertEnabled   = p.getBoolean("ae", c.alertEnabled);
        c.ledWifiEnabled = p.getBoolean("le", c.ledWifiEnabled);
        c.beepShortMs    = p.getInt("bs", c.beepShortMs);
        c.beepLongMs     = p.getInt("bl", c.beepLongMs);
        c.beepGapMs      = p.getInt("bg", c.beepGapMs);
        c.debounceMs     = p.getInt("db", c.debounceMs);
        c.maxRangeMm     = p.getInt("mr", c.maxRangeMm);
        c.volumePct      = p.getInt("vol", c.volumePct);
        String st        = p.getString("st", "TONE");
        try { c.soundType = AlertPatternConfig.SoundType.valueOf(st); }
        catch (Exception ignored) { c.soundType = AlertPatternConfig.SoundType.TONE; }
        c.shortSoundUri  = p.getString("uri_short", "");
        c.longSoundUri   = p.getString("uri_long",  "");
        String routing   = p.getString("routing", "NOTIFICATION");
        try { c.routing = AlertPatternConfig.Routing.valueOf(routing); }
        catch (Exception ignored) { c.routing = AlertPatternConfig.Routing.NOTIFICATION; }
        return c;
    }

    private void saveAlertPrefs(AlertPatternConfig c) {
        alertPrefs().edit()
                .putBoolean("ae", c.alertEnabled)
                .putBoolean("le", c.ledWifiEnabled)
                .putInt("bs", c.beepShortMs)
                .putInt("bl", c.beepLongMs)
                .putInt("bg", c.beepGapMs)
                .putInt("db", c.debounceMs)
                .putInt("mr", c.maxRangeMm)
                .putInt("vol", c.volumePct)
                .putString("st", c.soundType.name())
                .putString("uri_short", c.shortSoundUri == null ? "" : c.shortSoundUri)
                .putString("uri_long",  c.longSoundUri  == null ? "" : c.longSoundUri)
                .putString("routing", (c.routing == null
                        ? AlertPatternConfig.Routing.NOTIFICATION
                        : c.routing).name())
                .apply();
    }

    private int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
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
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Up to date")
                            .setMessage("You are on the latest version (v"
                                    + BuildConfig.VERSION_NAME + ").")
                            .setPositiveButton("OK", null)
                            .show();
                    break;
                case DISABLED:
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Update channel not configured")
                            .setMessage("This build was compiled without an UPDATE_MANIFEST_URL.\n\n"
                                    + "Install the signed APK from the GitHub Releases page to enable "
                                    + "the in-app updater.")
                            .setPositiveButton("OK", null)
                            .show();
                    break;
                case ERROR:
                default:
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Check failed")
                            .setMessage("Could not reach the update server.\n\n"
                                    + "Details: "
                                    + (result.errorMessage != null ? result.errorMessage : "unknown")
                                    + "\n\n"
                                    + "Verify the phone has internet access and try again. Detailed "
                                    + "logs are in adb logcat (tag: UpdateManager).")
                            .setPositiveButton("OK", null)
                            .show();
                    break;
            }
        });
    }

    @Override
    public void onDestroyView() {
        mqtt.removeConfigAckListener(this);
        if (cfgDiscoveryListener != null) {
            mqtt.removeDiscoveryListener(cfgDiscoveryListener);
            cfgDiscoveryListener = null;
        }
        try { wifiScanHelper.cancel(requireContext()); } catch (Exception ignored) {}
        alertHttp.shutdown();
        super.onDestroyView();
    }

    /** Build a sorted snapshot of every device name we've seen on
     *  humanradar/+/status. Empty list when no broker is connected yet. */
    private java.util.List<String> collectDiscoveredDeviceNames() {
        java.util.Set<String> set = new java.util.TreeSet<>();
        for (MqttService.DiscoveredDevice d : mqtt.getDiscoveredDevices()) {
            if (d != null && d.deviceName != null && !d.deviceName.isEmpty()) {
                set.add(d.deviceName);
            }
        }
        return new java.util.ArrayList<>(set);
    }

    private void onSendConfig(View v) {
        if (!mqtt.isConnected()) {
            Toast.makeText(requireContext(), "Not connected to MQTT", Toast.LENGTH_SHORT).show();
            return;
        }

        JsonObject config = new JsonObject();

        String name = cfgDeviceName.getText() != null
                ? cfgDeviceName.getText().toString().trim() : "";
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
