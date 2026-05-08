package com.example.radarhumanapplication.update;

import com.google.gson.annotations.SerializedName;

/**
 * Maps the JSON fields published in {@code update.json}. All fields are nullable so that an
 * older or partially-populated manifest does not crash the parser; callers should validate before
 * use.
 */
public class UpdateInfo {

    @SerializedName("versionCode")
    public int versionCode;

    @SerializedName("versionName")
    public String versionName;

    @SerializedName("minSdkVersion")
    public int minSdkVersion;

    @SerializedName("apkUrl")
    public String apkUrl;

    @SerializedName("sha256")
    public String sha256;

    @SerializedName("sizeBytes")
    public long sizeBytes;

    @SerializedName("mandatory")
    public boolean mandatory;

    @SerializedName("releaseNotes")
    public String releaseNotes;

    @SerializedName("publishedAt")
    public String publishedAt;

    public boolean isUsable() {
        return versionCode > 0
                && versionName != null && !versionName.isEmpty()
                && apkUrl != null && apkUrl.startsWith("https://")
                && sha256 != null && sha256.length() == 64;
    }
}
