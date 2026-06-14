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
        /** Upload finished, ESP32 is rebooting. Caller should now show a "waiting
         *  for the device to come back online" indicator and call
         *  {@link FirmwareUpdater#verifyOnMqtt} (or its own equivalent) to
         *  confirm the new firmware is running. */
        void onUploaded();
        /** Reboot verified via MQTT — the device returned with the expected
         *  firmware version. Carries the device's own info dump if provided. */
        void onInstalled(MqttVerifyResult info);
        void onError(String message);
    }

    /** Subset of fields the firmware publishes in {@code humanradar/<name>/info}
     *  after rebooting into the new build. */
    public static final class MqttVerifyResult {
        public final String deviceName;
        public final String fw;
        public final String ip;
        public final String mac;
        public final boolean versionMatches;
        public MqttVerifyResult(String deviceName, String fw, String ip,
                                String mac, boolean versionMatches) {
            this.deviceName = deviceName == null ? "" : deviceName;
            this.fw         = fw == null ? "" : fw;
            this.ip         = ip == null ? "" : ip;
            this.mac        = mac == null ? "" : mac;
            this.versionMatches = versionMatches;
        }
    }

    /** Default wait for the device to republish {@code /info} on the new fw.
     *  Generous because after flashing the ESP reboots, reconnects WiFi, then MQTT,
     *  then republishes — on a weak link that easily exceeds a minute, and reporting
     *  failure too early wrongly sends the user toward rollback/USB recovery. */
    public static final long DEFAULT_MQTT_VERIFY_TIMEOUT_MS = 180_000;

    /**
     * Pre-upload device snapshot from {@code GET /api/info}. Used by the
     * integrity panel + size validation before starting an OTA write.
     * Fields default to 0/"" when the device's firmware predates the
     * endpoint (returns 404), letting the UI gracefully degrade.
     */
    public static final class DeviceInfo {
        public final String fw;
        public final String ip;
        public final String mac;
        public final String name;
        public final long freeAppPartitionBytes;
        public final long runningPartitionBytes;
        public final long sketchSize;
        public final String sketchMd5;
        public final long freeHeap;
        public final long totalHeap;

        public DeviceInfo(String fw, String ip, String mac, String name,
                          long freeAppPartitionBytes, long runningPartitionBytes,
                          long sketchSize, String sketchMd5,
                          long freeHeap, long totalHeap) {
            this.fw = nz(fw); this.ip = nz(ip);
            this.mac = nz(mac); this.name = nz(name);
            this.freeAppPartitionBytes = freeAppPartitionBytes;
            this.runningPartitionBytes = runningPartitionBytes;
            this.sketchSize = sketchSize;
            this.sketchMd5 = nz(sketchMd5);
            this.freeHeap = freeHeap;
            this.totalHeap = totalHeap;
        }
        private static String nz(String s) { return s == null ? "" : s; }
    }

    public interface DeviceInfoCallback {
        /** {@code info} is null when the endpoint is unreachable or the
         *  device's firmware is too old to support {@code /api/info}. */
        void onDeviceInfo(DeviceInfo info, String error);
    }

    /** Fetch the device's rich info for the pre-upload integrity panel. */
    public void fetchDeviceInfo(String deviceIp, DeviceInfoCallback cb) {
        io.execute(() -> {
            DeviceInfo info = null;
            String err = null;
            try {
                String body = httpGet("http://" + deviceIp + "/api/info");
                com.google.gson.JsonObject o = com.google.gson.JsonParser
                        .parseString(body).getAsJsonObject();
                info = new DeviceInfo(
                        getStr(o, "fw"), getStr(o, "ip"),
                        getStr(o, "mac"), getStr(o, "name"),
                        getLong(o, "free_app_partition_bytes"),
                        getLong(o, "running_partition_bytes"),
                        getLong(o, "sketch_size"),
                        getStr(o, "sketch_md5"),
                        getLong(o, "free_heap"),
                        getLong(o, "total_heap"));
            } catch (Exception e) {
                Log.w(TAG, "fetchDeviceInfo failed: " + e.getMessage());
                err = e.getMessage();
            }
            final DeviceInfo finalInfo = info;
            final String finalErr = err;
            main.post(() -> cb.onDeviceInfo(finalInfo, finalErr));
        });
    }

    private static String getStr(com.google.gson.JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
        catch (Exception e) { return ""; }
    }
    private static long getLong(com.google.gson.JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L; }
        catch (Exception e) { return 0L; }
    }

    /**
     * Compute SHA-256 of a local file (the .bin downloaded from GitHub
     * Releases). Used by the integrity panel to show the user what's
     * about to be sent vs the manifest's expected hash.
     */
    public static String sha256OfFile(java.io.File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Compare two semver-ish strings ("1.0.48"). Returns negative if a&lt;b,
     * 0 if equal, positive if a&gt;b. Treats missing segments as 0 and
     * ignores trailing non-numeric suffixes ("1.0.48-beta" → 1,0,48).
     */
    public static int compareFwVersion(String a, String b) {
        int[] av = parseFw(a);
        int[] bv = parseFw(b);
        for (int i = 0; i < Math.max(av.length, bv.length); i++) {
            int x = i < av.length ? av[i] : 0;
            int y = i < bv.length ? bv[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
    private static int[] parseFw(String s) {
        if (s == null) return new int[0];
        String[] parts = s.split("[.\\-]");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { out[i] = 0; }
        }
        return out;
    }

    /**
     * Quick pre-flight reachability check. Hits {@code GET /api/version}
     * on the candidate IP with a short timeout and returns the parsed fw
     * version, or null if unreachable. Lets the install flow bail fast
     * with a clear error instead of silently "succeeding" against an
     * unreachable host.
     */
    public void probeReachability(String deviceIp, java.util.function.Consumer<String> cb) {
        io.execute(() -> {
            String fw = null;
            try {
                HttpURLConnection c = (HttpURLConnection)
                        new URL("http://" + deviceIp + "/api/version")
                                .openConnection();
                c.setConnectTimeout(3_000);
                c.setReadTimeout(3_000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                if (code / 100 == 2) {
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(c.getInputStream(),
                                    java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    com.google.gson.JsonObject o = com.google.gson.JsonParser
                            .parseString(sb.toString()).getAsJsonObject();
                    if (o.has("fw")) fw = o.get("fw").getAsString();
                    else fw = "?";
                }
                c.disconnect();
            } catch (Exception e) {
                Log.d(TAG, "probeReachability failed: " + e.getMessage());
            }
            final String fwFinal = fw;
            main.post(() -> cb.accept(fwFinal));
        });
    }

    /**
     * MQTT-pull OTA: publish an {@code ota_pull} command to the device
     * via the existing broker connection, telling it to download the
     * firmware .bin from {@code binUrl} itself. Works from anywhere with
     * MQTT + internet — no LAN reachability from the phone needed.
     *
     * <p>The {@link InstallCallback#onUploaded()} callback fires when the
     * command is published; the caller should then call
     * {@link #verifyOnMqtt} to wait for the device to come back with
     * the new fw version.
     */
    public void installViaMqtt(FirmwareManifest manifest, String deviceName,
                               InstallCallback cb) {
        if (manifest == null || !manifest.isUsable()) {
            main.post(() -> cb.onError("Manifest is empty"));
            return;
        }
        if (deviceName == null || deviceName.isEmpty()) {
            main.post(() -> cb.onError("Device name is required for MQTT OTA"));
            return;
        }
        com.example.radarhumanapplication.MqttService mqtt =
                com.example.radarhumanapplication.MqttService.getInstance();
        if (!mqtt.isConnected()) {
            main.post(() -> cb.onError("MQTT not connected — cannot send ota_pull"));
            return;
        }
        com.google.gson.JsonObject extras = new com.google.gson.JsonObject();
        extras.addProperty("url", manifest.binUrl);
        mqtt.sendCommandToWithExtras(deviceName, "ota_pull", extras);
        mqtt.injectAppLog("INFO", "OTA",
                "Sent ota_pull to " + deviceName + " → " + manifest.binUrl);
        main.post(() -> cb.onProgress(50, "Waiting for ESP32 to download + flash"));
        main.post(cb::onUploaded);
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
        com.example.radarhumanapplication.MqttService.getInstance().injectAppLog(
                "INFO", "OTA",
                "Uploading " + manifest.versionName + " (" + sizeBytes
                        + " B) to " + deviceIp);
        uploader.start(deviceIp, binUri, sizeBytes, new FirmwareUploader.Callback() {
            @Override public void onProgress(int percent) {
                main.post(() -> cb.onProgress(percent, "Uploading to ESP32"));
            }
            @Override public void onCompleted() {
                safeDelete(binFile);
                com.example.radarhumanapplication.MqttService.getInstance().injectAppLog(
                        "INFO", "OTA",
                        "Upload to " + deviceIp + " accepted, waiting for "
                                + "device to reboot…");
                main.post(cb::onUploaded);
            }
            @Override public void onError(String message) {
                safeDelete(binFile);
                com.example.radarhumanapplication.MqttService.getInstance().injectAppLog(
                        "ERROR", "OTA",
                        "Upload to " + deviceIp + " failed: " + message);
                main.post(() -> cb.onError(message));
            }
        });
    }

    /**
     * Wait for the ESP32 to come back on MQTT after an OTA. The firmware
     * publishes retained {@code humanradar/<name>/info} on every boot with
     * its current {@code fw} field — that's our confirmation.
     *
     * <p>Snapshots the existing info state at call time so a stale retained
     * message from the old version doesn't trigger a false positive: only
     * an info message with {@code fw == expectedVersion} that arrives AFTER
     * this call counts as a verification.
     */
    public void verifyOnMqtt(String expectedDeviceName, String expectedVersion,
                             long timeoutMs, InstallCallback cb) {
        verifyOnMqtt(expectedDeviceName, expectedVersion, /*deviceIpHint=*/ null,
                timeoutMs, cb);
    }

    /**
     * Same as the 4-arg overload, but also HTTP-polls {@code /api/version}
     * on {@code deviceIpHint} in parallel. Whichever signal arrives first —
     * MQTT /info or a successful HTTP GET — wins. This stops the dialog
     * from sitting on "Timeout" when the broker is unreachable but the
     * device IS back on LAN.
     */
    public void verifyOnMqtt(String expectedDeviceName, String expectedVersion,
                             String deviceIpHint, long timeoutMs,
                             InstallCallback cb) {
        if (expectedDeviceName == null || expectedDeviceName.isEmpty()) {
            main.post(() -> cb.onError("Cannot verify — device name unknown"));
            return;
        }
        final com.example.radarhumanapplication.MqttService mqtt =
                com.example.radarhumanapplication.MqttService.getInstance();
        mqtt.injectAppLog("INFO", "OTA",
                "Verifying " + expectedDeviceName + " came back with fw="
                        + expectedVersion + " (timeout " + (timeoutMs / 1000) + "s)"
                        + (deviceIpHint == null ? "" : ", HTTP poll " + deviceIpHint));
        final long startedAt = System.currentTimeMillis();

        // Already on the new version? (Rare but possible if /info beat the OTA
        // upload response back to us — accept and move on.)
        for (com.example.radarhumanapplication.MqttService.DiscoveredDevice d
                : mqtt.getDiscoveredDevices()) {
            if (d == null) continue;
            if (expectedDeviceName.equals(d.deviceName)
                    && expectedVersion != null
                    && expectedVersion.equals(d.fw)
                    && d.lastSeenMs >= startedAt - 1000) {
                main.post(() -> cb.onInstalled(new MqttVerifyResult(
                        d.deviceName, d.fw, d.ip, d.mac, true)));
                return;
            }
        }

        final com.example.radarhumanapplication.MqttService.DiscoveryListener[] holder =
                new com.example.radarhumanapplication.MqttService.DiscoveryListener[1];
        final Runnable[] timeoutHolder = new Runnable[1];
        final boolean[] settled = {false};

        holder[0] = (deviceName, status, lastSeenMs) -> {
            synchronized (holder) {
                if (settled[0]) return;
                if (!expectedDeviceName.equals(deviceName)) return;
                com.example.radarhumanapplication.MqttService.DiscoveredDevice d =
                        findDevice(mqtt, deviceName);
                if (d == null || d.fw == null || d.fw.isEmpty()) return;
                boolean match = expectedVersion == null
                        || expectedVersion.isEmpty()
                        || expectedVersion.equals(d.fw);
                if (!match) return;
                settled[0] = true;
            }
            mqtt.removeDiscoveryListener(holder[0]);
            if (timeoutHolder[0] != null) main.removeCallbacks(timeoutHolder[0]);
            com.example.radarhumanapplication.MqttService.DiscoveredDevice d =
                    findDevice(mqtt, deviceName);
            String fw  = d != null ? d.fw  : (expectedVersion == null ? "" : expectedVersion);
            String ip  = d != null ? d.ip  : "";
            String mac = d != null ? d.mac : "";
            mqtt.injectAppLog("INFO", "OTA",
                    "✓ " + deviceName + " came back on MQTT with fw=" + fw
                            + " (expected " + expectedVersion + ")");
            main.post(() -> cb.onInstalled(new MqttVerifyResult(
                    deviceName, fw, ip, mac, true)));
        };
        mqtt.addDiscoveryListener(holder[0]);

        // Optional HTTP poll — runs every 4s against the device's IP. First
        // 200 OK with fw matching expectedVersion wins, even if MQTT /info
        // never arrives. This is the "Timeout" escape hatch.
        final java.util.concurrent.atomic.AtomicReference<Runnable> pollHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        if (deviceIpHint != null && !deviceIpHint.isEmpty()) {
            pollHolder.set(new Runnable() {
                int attempt = 0;
                @Override public void run() {
                    if (settled[0]) return;
                    attempt++;
                    io.execute(() -> {
                        if (settled[0]) return;
                        String fw = "";
                        String err = null;
                        try {
                            String body = httpGet("http://" + deviceIpHint
                                    + "/api/version");
                            com.google.gson.JsonObject o = com.google.gson.JsonParser
                                    .parseString(body).getAsJsonObject();
                            if (o.has("fw")) fw = o.get("fw").getAsString();
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                        final String fwFinal = fw;
                        final String errFinal = err;
                        main.post(() -> {
                            if (settled[0]) return;
                            if (!fwFinal.isEmpty()) {
                                boolean match = expectedVersion == null
                                        || expectedVersion.isEmpty()
                                        || expectedVersion.equals(fwFinal);
                                mqtt.injectAppLog(match ? "INFO" : "WARN", "OTA",
                                        "HTTP poll #" + attempt + " "
                                                + deviceIpHint + " → fw=" + fwFinal
                                                + (match ? " ✓ match"
                                                         : " (expected " + expectedVersion + ")"));
                                if (match) {
                                    synchronized (holder) {
                                        if (settled[0]) return;
                                        settled[0] = true;
                                    }
                                    mqtt.removeDiscoveryListener(holder[0]);
                                    if (timeoutHolder[0] != null)
                                        main.removeCallbacks(timeoutHolder[0]);
                                    cb.onInstalled(new MqttVerifyResult(
                                            expectedDeviceName, fwFinal,
                                            deviceIpHint, "", true));
                                    return;
                                }
                            } else if (errFinal != null && attempt == 1) {
                                // Only log the first failure to avoid noise.
                                mqtt.injectAppLog("DEBUG", "OTA",
                                        "HTTP poll #" + attempt + " "
                                                + deviceIpHint + " → " + errFinal);
                            }
                            // Schedule next attempt.
                            if (!settled[0]) {
                                main.postDelayed(pollHolder.get(), 4_000);
                            }
                        });
                    });
                }
            });
            // First attempt fires 3s after upload completes — gives the chip
            // time to finish writing flash + reboot before we hammer it.
            main.postDelayed(pollHolder.get(), 3_000);
        }

        timeoutHolder[0] = () -> {
            synchronized (holder) {
                if (settled[0]) return;
                settled[0] = true;
            }
            mqtt.removeDiscoveryListener(holder[0]);
            // Surface what we DID see during the timeout window so the user
            // doesn't have to guess. Common pattern: device came back but
            // with the OLD fw — that means OTA silently failed and the chip
            // booted from the previous partition.
            com.example.radarhumanapplication.MqttService.DiscoveredDevice cur =
                    findDevice(mqtt, expectedDeviceName);
            String detail;
            if (cur == null) {
                detail = "no /info from broker — broker unreachable, "
                        + "device's WiFi credentials wrong, or device powered off";
            } else if (cur.fw == null || cur.fw.isEmpty()) {
                detail = "device on broker but no fw published yet — try again "
                        + "or check Logs tab for ESP boot messages";
            } else if (expectedVersion != null && !expectedVersion.equals(cur.fw)) {
                detail = "device came back with fw=" + cur.fw + " (expected v"
                        + expectedVersion + ") — OTA likely rejected, "
                        + "chip rolled back to previous partition";
            } else {
                detail = "device reachable but verification race — should be fine";
            }
            mqtt.injectAppLog("WARN", "OTA",
                    "Verify timeout after " + (timeoutMs / 1000) + "s: " + detail);
            main.post(() -> cb.onError("Timeout after " + (timeoutMs / 1000)
                    + "s — " + detail));
        };
        main.postDelayed(timeoutHolder[0], timeoutMs);
    }

    private static com.example.radarhumanapplication.MqttService.DiscoveredDevice
            findDevice(com.example.radarhumanapplication.MqttService mqtt, String name) {
        for (com.example.radarhumanapplication.MqttService.DiscoveredDevice d
                : mqtt.getDiscoveredDevices()) {
            if (d != null && name.equals(d.deviceName)) return d;
        }
        return null;
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
