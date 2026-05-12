package com.example.radarhumanapplication.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.radarhumanapplication.BuildConfig;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide entry point for "is there a newer APK on the manifest URL?" checks.
 *
 * The manifest URL is injected at build time via {@code BuildConfig.UPDATE_MANIFEST_URL}.
 * Callers either trigger a background check (auto-debounced 6h) or force a manual check.
 * Skipped-version state survives reinstalls only as a UX nicety, not a security control.
 */
public class UpdateManager {

    private static final String TAG = "UpdateManager";
    private static final String PREFS = "update_prefs";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    private static final String KEY_SKIPPED_VERSION = "skipped_version_code";

    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private static final UpdateManager INSTANCE = new UpdateManager();

    public static UpdateManager getInstance() {
        return INSTANCE;
    }

    public interface CheckCallback {
        /** Called on the main thread. */
        void onResult(Result result);
    }

    public enum Status { UPDATE_AVAILABLE, UP_TO_DATE, ERROR, SKIPPED, DISABLED }

    public static class Result {
        public final Status status;
        public final UpdateInfo info;       // non-null only when UPDATE_AVAILABLE
        public final String errorMessage;   // non-null when ERROR

        Result(Status status, UpdateInfo info, String errorMessage) {
            this.status = status;
            this.info = info;
            this.errorMessage = errorMessage;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    private UpdateManager() {}

    public void checkAuto(Context ctx, CheckCallback cb) {
        SharedPreferences prefs = prefs(ctx);
        long last = prefs.getLong(KEY_LAST_CHECK_MS, 0L);
        long now = System.currentTimeMillis();
        if (now - last < AUTO_CHECK_INTERVAL_MS) {
            // Skip silently — caller treats no callback firing as "nothing to show"
            return;
        }
        check(ctx, /* userInitiated= */ false, cb);
    }

    public void checkManual(Context ctx, CheckCallback cb) {
        check(ctx, /* userInitiated= */ true, cb);
    }

    private void check(Context ctx, boolean userInitiated, CheckCallback cb) {
        String url = BuildConfig.UPDATE_MANIFEST_URL;
        Log.i(TAG, "check(userInitiated=" + userInitiated + ") url=" + url
                + " currentVersionCode=" + BuildConfig.VERSION_CODE
                + " currentVersionName=" + BuildConfig.VERSION_NAME);
        if (url == null || url.isEmpty() || url.contains("REPLACE_ME")) {
            deliver(cb, new Result(Status.DISABLED, null, "Update manifest URL not configured"));
            return;
        }
        Context app = ctx.getApplicationContext();
        io.execute(() -> {
            try {
                String json = fetchString(url);
                Log.i(TAG, "Manifest fetched (" + json.length() + " bytes)");
                UpdateInfo info = gson.fromJson(json, UpdateInfo.class);
                prefs(app).edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply();

                if (info == null || !info.isUsable()) {
                    Log.w(TAG, "Manifest malformed: " + json);
                    deliver(cb, new Result(Status.ERROR, null, "Manifest is malformed"));
                    return;
                }
                Log.i(TAG, "Manifest: vc=" + info.versionCode + " vn=" + info.versionName
                        + " minSdk=" + info.minSdkVersion + " mandatory=" + info.mandatory);
                if (Build.VERSION.SDK_INT < info.minSdkVersion) {
                    deliver(cb, new Result(Status.UP_TO_DATE, null, null));
                    return;
                }
                if (info.versionCode <= BuildConfig.VERSION_CODE) {
                    Log.i(TAG, "Already on latest (current=" + BuildConfig.VERSION_CODE
                            + ", manifest=" + info.versionCode + ")");
                    deliver(cb, new Result(Status.UP_TO_DATE, null, null));
                    return;
                }
                int skipped = prefs(app).getInt(KEY_SKIPPED_VERSION, 0);
                if (!userInitiated && !info.mandatory && skipped >= info.versionCode) {
                    deliver(cb, new Result(Status.SKIPPED, info, null));
                    return;
                }
                Log.i(TAG, "Update available: " + info.versionName);
                deliver(cb, new Result(Status.UPDATE_AVAILABLE, info, null));
            } catch (Exception e) {
                Log.w(TAG, "Update check failed", e);
                deliver(cb, new Result(Status.ERROR, null, e.getMessage()));
            }
        });
    }

    public void skipVersion(Context ctx, int versionCode) {
        prefs(ctx).edit().putInt(KEY_SKIPPED_VERSION, versionCode).apply();
    }

    public void clearSkip(Context ctx) {
        prefs(ctx).edit().remove(KEY_SKIPPED_VERSION).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void deliver(CheckCallback cb, Result r) {
        if (cb == null) return;
        main.post(() -> cb.onResult(r));
    }

    private String fetchString(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code);
            }
            try (InputStream is = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[1024];
                int n;
                while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
                return sb.toString();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
