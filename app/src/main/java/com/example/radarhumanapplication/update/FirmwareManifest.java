package com.example.radarhumanapplication.update;

/**
 * Parses the {@code firmware.json} published as a GitHub Release asset. Same idea as
 * {@link UpdateInfo} for the APK, but for ESP32 firmware images.
 */
public class FirmwareManifest {
    public String versionName = "";
    public int    versionCode = 0;
    public String binUrl      = "";
    public String sha256      = "";
    public long   sizeBytes   = 0;
    public String releaseNotes = "";
    public String publishedAt  = "";

    public boolean isUsable() {
        return versionName != null && !versionName.isEmpty()
                && binUrl  != null && !binUrl.isEmpty();
    }
}
