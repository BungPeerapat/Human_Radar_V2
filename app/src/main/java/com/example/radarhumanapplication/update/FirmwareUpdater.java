package com.example.radarhumanapplication.update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates a firmware update for an ESP32: fetch the manifest from GitHub Releases,
 * compare with the device's currently running version ({@code GET /api/version}), download
 * the .bin to the app cache, verify SHA-256, and push it to the device via
 * {@link FirmwareUploader}.
 *
 * <p>All callbacks fire on the main thread. Cancelling stops the in-flight download or upload
 * gracefully.</p>
 */
public final class FirmwareUpdater {

    private static final String TAG = "FirmwareUpdater";
    /** Default manifest location — same pattern as the APK update.json. */
    public static final String DEFAULT_MANIFEST_URL =
            "https://github.com/BungPeerapat/Human_Radar_V2/releases/latest/download/firmware.json";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 60_000;
    private static final int BUFFER_SIZE        = 16 * 1024;
    private static final int PROGRESS_CHUNK     = 64 * 1024;

    public interface CheckCallback {
        /** Called on main thread with the parsed manifest + the device's reported version
         *  (empty string if /api/version was unreachable). */
        void onChecked(FirmwareManifest manifest, String deviceVersion, String error);
    }

    public interface InstallCallback {
        void onProgress(int phasePercent, String phaseLabel);
        void onInstalled();
        void onError(String message);
    }

    private final Context appContext;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();
    private final FirmwareUploader uploader;
    private volatile boolean cancelled = false;

    public FirmwareUpdater(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.uploader   = new FirmwareUploader(appContext);
    }

    public void cancel() {
        cancelled = true;
        uploader.cancel();
    }

    // ------------------------------------------------------------------
    //  Check: fetch manifest + (optionally) query the device's version
    // ------------------------------------------------------------------
    public void check(String manifestUrl, String deviceIpOrNull, CheckCallback cb) {
        final String url = (manifestUrl == null || manifestUrl.isEmpty())
                ? DEFAULT_MANIFEST_URL : manifestUrl;
        io.execute(() -> {
            FirmwareManifest manifest = null;
            String deviceVersion = "";
            String err = null;
            try {
                String body = httpGet(url);
                Log.i(TAG, "Manifest fetched (" + body.length() + " bytes) from " + url);
                manifest = gson.fromJson(body, FirmwareManifest.class);
                if (manifest == null || !manifest.isUsable()) {
                    err = "Firmware manifest is malformed";
                }
            } catch (Exception e) {
                Log.w(TAG, "Manifest fetch failed", e);
                err = "Fetch failed: " + e.getMessage();
            }
            if (deviceIpOrNull != null && !deviceIpOrNull.isEmpty()) {
                try {
                    String body = httpGet("http://" + deviceIpOrNull + "/api/version");
                    JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                    if (o.has("fw")) deviceVersion = o.get("fw").getAsString();
                    Log.i(TAG, "Device " + deviceIpOrNull + " reports fw=" + deviceVersion);
                } catch (Exception e) {
                    Log.w(TAG, "Device /api/version unreachable", e);
                }
            }
            final FirmwareManifest finalManifest = manifest;
            final String finalDeviceVersion = deviceVersion;
            final String finalErr = err;
            main.post(() -> cb.onChecked(finalManifest, finalDeviceVersion, finalErr));
        });
    }

    // ------------------------------------------------------------------
    //  Install: download .bin to cache + upload to ESP32
    // ------------------------------------------------------------------
    public void install(FirmwareManifest manifest, String deviceIp, InstallCallback cb) {
        cancelled = false;
        if (manifest == null || !manifest.isUsable()) {
            main.post(() -> cb.onError("Manifest is empty"));
            return;
        }
        if (deviceIp == null || deviceIp.isEmpty()) {
            main.post(() -> cb.onError("Device IP is missing"));
            return;
        }
        io.execute(() -> downloadThenUpload(manifest, deviceIp, cb));
    }

    private void downloadThenUpload(FirmwareManifest manifest, String deviceIp,
                                    InstallCallback cb) {
        File cacheDir = appContext.getCacheDir();
        File binFile  = new File(cacheDir, "firmware-" + manifest.versionName + ".bin");
        if (binFile.exists() && !binFile.delete()) {
            Log.w(TAG, "Could not delete stale firmware at " + binFile);
        }

        // Phase 1: download manifest.binUrl into cache
        HttpURLConnection conn = null;
        InputStream  in  = null;
        OutputStream out = null;
        try {
            URL url = new URL(manifest.binUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                postErr(cb, "Download HTTP " + code);
                return;
            }
            long total = conn.getContentLengthLong();
            if (total <= 0 && manifest.sizeBytes > 0) total = manifest.sizeBytes;

            in  = conn.getInputStream();
            out = new FileOutputStream(binFile);
            byte[] buf = new byte[BUFFER_SIZE];
            long sent = 0;
            long sinceLastReport = 0;
            int lastPct = -1;
            int read;
            while ((read = in.read(buf)) > 0) {
                if (cancelled) {
                    safeDelete(binFile);
                    postErr(cb, "Cancelled");
                    return;
                }
                out.write(buf, 0, read);
                sent += read;
                sinceLastReport += read;
                if (total > 0 && sinceLastReport >= PROGRESS_CHUNK) {
                    sinceLastReport = 0;
                    int pct = (int) Math.min(99, sent * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        final int p = pct;
                        main.post(() -> cb.onProgress(p, "Downloading firmware"));
                    }
                }
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;
            main.post(() -> cb.onProgress(100, "Download complete"));

            if (manifest.sha256 != null && !manifest.sha256.isEmpty()) {
                String actual = sha256(binFile);
                if (!actual.equalsIgnoreCase(manifest.sha256)) {
                    safeDelete(binFile);
                    postErr(cb, "Checksum mismatch — firmware rejected");
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Firmware download failed", e);
            safeDelete(binFile);
            postErr(cb, "Download error: " + e.getMessage());
            return;
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }

        // Phase 2: upload to ESP32 via existing FirmwareUploader
        main.post(() -> cb.onProgress(0, "Uploading to ESP32"));
        android.net.Uri binUri = android.net.Uri.fromFile(binFile);
        long sizeBytes = binFile.length();
        uploader.start(deviceIp, binUri, sizeBytes, new FirmwareUploader.Callback() {
            @Override public void onProgress(int percent) {
                main.post(() -> cb.onProgress(percent, "Uploading to ESP32"));
            }
            @Override public void onCompleted() {
                safeDelete(binFile);
                main.post(cb::onInstalled);
            }
            @Override public void onError(String message) {
                safeDelete(binFile);
                main.post(() -> cb.onError(message));
            }
        });
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------
    private void postErr(InstallCallback cb, String msg) {
        main.post(() -> cb.onError(msg != null ? msg : "Unknown error"));
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new RuntimeException("HTTP " + code);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(f)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static void safeDelete(File f) {
        if (f != null && f.exists() && !f.delete()) {
            Log.w(TAG, "Could not delete " + f);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }
}
