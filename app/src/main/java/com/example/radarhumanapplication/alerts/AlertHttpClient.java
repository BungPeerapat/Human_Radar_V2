package com.example.radarhumanapplication.alerts;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP client for the ESP32 {@code /api/alert*} endpoints. Runs requests
 * on a background single-thread executor and reports back on the main looper.
 */
public final class AlertHttpClient {

    private static final String TAG = "AlertHttpClient";
    private static final int TIMEOUT_MS = 5000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main      = new Handler(Looper.getMainLooper());

    public interface Callback<T> {
        void onResult(T result, String error);
    }

    /** GET http://{deviceIp}/api/alert */
    public void fetchConfig(String deviceIp, Callback<AlertPatternConfig> cb) {
        io.execute(() -> {
            try {
                String body = httpGet("http://" + deviceIp + "/api/alert");
                JsonObject o = JsonParser.parseString(body).getAsJsonObject();
                AlertPatternConfig cfg = AlertPatternConfig.fromEspJson(o);
                main.post(() -> cb.onResult(cfg, null));
            } catch (Exception e) {
                Log.w(TAG, "fetchConfig failed", e);
                main.post(() -> cb.onResult(null, e.getMessage()));
            }
        });
    }

    /** POST http://{deviceIp}/api/alert */
    public void pushConfig(String deviceIp, AlertPatternConfig cfg, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                httpPost("http://" + deviceIp + "/api/alert", cfg.toEspJson().toString());
                main.post(() -> cb.onResult(true, null));
            } catch (Exception e) {
                Log.w(TAG, "pushConfig failed", e);
                main.post(() -> cb.onResult(false, e.getMessage()));
            }
        });
    }

    /** POST http://{deviceIp}/api/alert/test  body={"count":N,"long":0|1} */
    public void testBeep(String deviceIp, int count, boolean longPrefix, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("count", count);
                body.addProperty("long",  longPrefix ? 1 : 0);
                httpPost("http://" + deviceIp + "/api/alert/test", body.toString());
                main.post(() -> cb.onResult(true, null));
            } catch (Exception e) {
                Log.w(TAG, "testBeep failed", e);
                main.post(() -> cb.onResult(false, e.getMessage()));
            }
        });
    }

    public void shutdown() {
        io.shutdownNow();
    }

    /** GET http://{deviceIp}/api/config — returns the device's full config blob. */
    public void fetchDeviceConfig(String deviceIp, Callback<JsonObject> cb) {
        io.execute(() -> {
            try {
                String body = httpGet("http://" + deviceIp + "/api/config");
                JsonObject o = com.google.gson.JsonParser
                        .parseString(body).getAsJsonObject();
                main.post(() -> cb.onResult(o, null));
            } catch (Exception e) {
                Log.w(TAG, "fetchDeviceConfig failed", e);
                main.post(() -> cb.onResult(null, e.getMessage()));
            }
        });
    }

    /**
     * POST http://{deviceIp}/api/config with WiFi credentials only.
     *
     * @param mode 0 = AP (device hosts its own WiFi); 1 = STA (join the user's WiFi)
     * @param ssid network name (empty when mode = AP)
     * @param pass network password (empty when mode = AP or open network)
     * @param autoReconnect 1 = "Auto Find WiFi" (keep retrying STA + AP rescue); 0 = off
     *
     * The device persists the values to NVS and calls ESP.restart() so the
     * connection is dropped while the response is in flight — that's treated as
     * a success.
     */
    public void updateWifi(String deviceIp, int mode, String ssid, String pass,
                           int autoReconnect, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("wm", mode);
                body.addProperty("ws", ssid == null ? "" : ssid);
                body.addProperty("wp", pass == null ? "" : pass);
                body.addProperty("ar", autoReconnect);   // "Auto Find WiFi" toggle
                // Device may also be expecting MQTT keys to be present; send empty
                // sentinels so the handler's getJsonInt() defaults don't overwrite
                // existing values. Top-level handlers ignore unknown keys.
                httpPost("http://" + deviceIp + "/api/config", body.toString());
                main.post(() -> cb.onResult(true, null));
            } catch (Exception e) {
                // ESP.restart() drops the socket before the body finishes — that's a
                // good outcome here. Anything that looks like a connection-reset means
                // the device accepted the config and is rebooting.
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("EOF") || msg.contains("reset")
                        || msg.contains("closed") || msg.contains("aborted")) {
                    main.post(() -> cb.onResult(true, null));
                } else {
                    Log.w(TAG, "updateWifi failed", e);
                    main.post(() -> cb.onResult(false, msg));
                }
            }
        });
    }

    /**
     * POST a raw {@code /api/config} body. Used by the WiFi-update flow when
     * the app wants to send a merged config to firmware < v1.0.29 that
     * otherwise overwrites every section.
     */
    public void postRawConfig(String deviceIp, JsonObject body, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                httpPost("http://" + deviceIp + "/api/config", body.toString());
                main.post(() -> cb.onResult(true, null));
            } catch (Exception e) {
                // /api/config triggers ESP.restart() — a dropped socket means success.
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("EOF") || msg.contains("reset")
                        || msg.contains("closed") || msg.contains("aborted")) {
                    main.post(() -> cb.onResult(true, null));
                } else {
                    Log.w(TAG, "postRawConfig failed", e);
                    main.post(() -> cb.onResult(false, msg));
                }
            }
        });
    }

    /** POST http://{deviceIp}/api/firmware-rollback — boots the previous OTA partition. */
    public void rollbackFirmware(String deviceIp, Callback<Boolean> cb) {
        io.execute(() -> {
            try {
                httpPost("http://" + deviceIp + "/api/firmware-rollback", "{}");
                main.post(() -> cb.onResult(true, null));
            } catch (Exception e) {
                // Device reboots before sending response — treat dropped socket as success.
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("EOF") || msg.contains("reset") || msg.contains("closed")) {
                    main.post(() -> cb.onResult(true, null));
                } else {
                    main.post(() -> cb.onResult(false, msg));
                }
            }
        });
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new RuntimeException("HTTP " + code);
            return readAll(c);
        } finally {
            c.disconnect();
        }
    }

    private static void httpPost(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new RuntimeException("HTTP " + code);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
