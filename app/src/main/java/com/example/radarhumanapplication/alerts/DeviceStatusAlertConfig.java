package com.example.radarhumanapplication.alerts;

/**
 * Per-device configuration for the "device online/offline" notification system.
 *
 * <p>Each ESP32 device the app talks to over MQTT can have its own pair of sound clips —
 * one for the transition into "online", one for the transition into "offline". The clips
 * are user-picked content URIs (via Storage Access Framework) so they survive reboot.</p>
 *
 * <p>The app currently talks to a single device at a time, but {@link DeviceStatusAlertManager}
 * stores these by device name in a {@code Map} so adding multi-device support later is a UI
 * change only — no schema migration needed.</p>
 */
public class DeviceStatusAlertConfig {
    public String deviceName;
    public boolean onlineEnabled   = true;
    public boolean offlineEnabled  = true;
    public String  onlineSoundUri  = "";   // content:// URI or empty for none
    public String  offlineSoundUri = "";

    public DeviceStatusAlertConfig() {}

    public DeviceStatusAlertConfig(String deviceName) {
        this.deviceName = deviceName;
    }

    public DeviceStatusAlertConfig copy() {
        DeviceStatusAlertConfig c = new DeviceStatusAlertConfig(deviceName);
        c.onlineEnabled   = onlineEnabled;
        c.offlineEnabled  = offlineEnabled;
        c.onlineSoundUri  = onlineSoundUri  == null ? "" : onlineSoundUri;
        c.offlineSoundUri = offlineSoundUri == null ? "" : offlineSoundUri;
        return c;
    }
}
