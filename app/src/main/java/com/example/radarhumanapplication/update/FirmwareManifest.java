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
        if (versionName == null || versionName.trim().isEmpty()
                || sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")
                || sizeBytes <= 0 || sizeBytes > 16L * 1024 * 1024) return false;
        try {
            java.net.URI uri = new java.net.URI(binUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getRawUserInfo() == null;
        } catch (Exception invalidUrl) {
            return false;
        }
    }
}
