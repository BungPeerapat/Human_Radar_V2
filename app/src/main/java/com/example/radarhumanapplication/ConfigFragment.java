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

    private TextInputEditText cfgDeviceName, cfgPublishInterval, cfgUnmannedDelay, cfgTargetTimeout;
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
    private Slider alertVolume;
    private TextView alertVolumeLabel, alertStatus;
    private MaterialButton btnAlertFetch, btnAlertPush, btnAlertTestLocal, btnAlertTestRemote;
    private MaterialButton btnPickShort, btnPickLong;
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
    private LinearProgressIndicator fwProgress;
    private Uri pickedFirmwareUri;
    private long pickedFirmwareSize;
    private String pickedFirmwareName = "";
    private ActivityResultLauncher<String[]> pickFirmwareLauncher;
    private FirmwareUploader firmwareUploader;
    private com.example.radarhumanapplication.update.FirmwareUpdater firmwareUpdater;

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
        tvAppVersion.setText("Current: v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");

        btnSendConfig.setOnClickListener(this::onSendConfig);
        btnCheckUpdate.setOnClickListener(this::onCheckUpdate);
        mqtt.addConfigAckListener(this);

        bindAlertUi(v);
        bindDeviceStatusAlertUi(v);
        bindFirmwareOtaUi(v);
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
        fwStatus      = v.findViewById(R.id.fw_status);
        firmwareUploader = new FirmwareUploader(requireContext());
        firmwareUpdater  = new com.example.radarhumanapplication.update.FirmwareUpdater(requireContext());

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
                setFwStatus("Profile \"" + p.name + "\" has no device IP", true);
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
        fwProgress.setVisibility(View.VISIBLE);
        fwProgress.setProgress(0);
        setFwStatus("Uploading to " + ip + "...", false);

        firmwareUploader.start(ip, pickedFirmwareUri, pickedFirmwareSize,
                new FirmwareUploader.Callback() {
                    @Override public void onProgress(int percent) {
                        if (!isAdded()) return;
                        fwProgress.setProgress(percent);
                        setFwStatus("Uploading... " + percent + "%", false);
                    }

                    @Override public void onCompleted() {
                        if (!isAdded()) return;
                        fwProgress.setProgress(100);
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
                        fwProgress.setVisibility(View.GONE);
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
                setFwStatus("Profile \"" + p.name + "\" has no device IP", true);
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
        firmwareUpdater.check(
                com.example.radarhumanapplication.update.FirmwareUpdater.DEFAULT_MANIFEST_URL,
                ip,
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
                    String devLine = deviceVersion.isEmpty()
                            ? "Device (" + label + " @ " + ip + "): unknown (device offline?)"
                            : "Device (" + label + " @ " + ip + "): v" + deviceVersion;
                    String latestLine = "Latest: v" + manifest.versionName
                            + "  (" + (manifest.sizeBytes / 1024) + " KB)";
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
                    }
                    b.show();
                });
    }

    private void startFirmwareInstall(
            com.example.radarhumanapplication.update.FirmwareManifest manifest,
            String ip, String label) {
        btnFwCheck.setEnabled(false);
        btnFwPick.setEnabled(false);
        btnFwUpload.setEnabled(false);
        fwProgress.setVisibility(View.VISIBLE);
        fwProgress.setProgress(0);
        setFwStatus("Starting…", false);

        firmwareUpdater.install(manifest, ip,
                new com.example.radarhumanapplication.update.FirmwareUpdater.InstallCallback() {
                    @Override public void onProgress(int percent, String phase) {
                        if (!isAdded()) return;
                        fwProgress.setProgress(percent);
                        setFwStatus(phase + "… " + percent + "%", false);
                    }
                    @Override public void onInstalled() {
                        if (!isAdded()) return;
                        fwProgress.setProgress(100);
                        btnFwCheck.setEnabled(true);
                        btnFwPick.setEnabled(true);
                        btnFwUpload.setEnabled(pickedFirmwareUri != null);
                        setFwStatus("ESP32 (" + label + ") rebooting into v"
                                + manifest.versionName, false);
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Firmware installed")
                                .setMessage("ESP32 (" + label + ") rebooted into v"
                                        + manifest.versionName + ".\n\n"
                                        + "MQTT will reconnect automatically once the device "
                                        + "comes back online.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    @Override public void onError(String message) {
                        if (!isAdded()) return;
                        fwProgress.setVisibility(View.GONE);
                        btnFwCheck.setEnabled(true);
                        btnFwPick.setEnabled(true);
                        btnFwUpload.setEnabled(pickedFirmwareUri != null);
                        setFwStatus("Install failed: " + message, true);
                    }
                });
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
        alertSoundTone   = v.findViewById(R.id.alert_sound_tone);
        alertSoundWav    = v.findViewById(R.id.alert_sound_wav);
        alertSoundTts    = v.findViewById(R.id.alert_sound_tts);
        alertVolume      = v.findViewById(R.id.alert_volume);
        alertVolumeLabel = v.findViewById(R.id.alert_volume_label);
        alertStatus      = v.findViewById(R.id.alert_status);
        btnAlertFetch       = v.findViewById(R.id.btn_alert_fetch);
        btnAlertPush        = v.findViewById(R.id.btn_alert_push);
        btnAlertTestLocal   = v.findViewById(R.id.btn_alert_test_local);
        btnAlertTestRemote  = v.findViewById(R.id.btn_alert_test_remote);
        btnPickShort        = v.findViewById(R.id.btn_pick_short);
        btnPickLong         = v.findViewById(R.id.btn_pick_long);
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
                        setAlertStatus("Profile \"" + p.name + "\" has no device IP", true);
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
                setAlertStatus("Profile \"" + p.name + "\" has no device IP", true);
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
                setAlertStatus("Profile \"" + p.name + "\" has no device IP", true);
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
            if (Boolean.TRUE.equals(ok)) setAlertStatus("ESP32 test sent to " + ip, false);
            else setAlertStatus("ESP32 test failed: " + err, true);
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
        alertHttp.shutdown();
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
