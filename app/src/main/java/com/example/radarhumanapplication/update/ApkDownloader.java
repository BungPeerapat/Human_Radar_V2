package com.example.radarhumanapplication.update;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches the APK over HTTP(S) directly into the app's external files dir and verifies its
 * SHA-256 against the value declared in the manifest.
 *
 * <p><b>v3 rewrite (May 2026):</b> the original implementation delegated to Android's
 * {@link android.app.DownloadManager}, which spawns its own system notification and (on some
 * OEM ROMs) a download-manager UI when the user taps the notification. Users reported that
 * tapping the notification dropped them into Downloads instead of the install screen. This
 * direct downloader removes the DownloadManager from the flow entirely so the next thing the
 * user sees after the progress bar finishes is the system package installer dialog.</p>
 */
public class ApkDownloader {

    private static final String TAG = "ApkDownloader";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 30_000;
    private static final int BUFFER_SIZE        = 16 * 1024;
    /** Progress callbacks fire every N bytes to keep UI updates cheap. */
    private static final int PROGRESS_CHUNK     = 64 * 1024;

    public interface Callback {
        /** Called on the main thread once the APK is verified and ready to install. */
        void onReady(File apk);

        /** Called on the main thread when the download or verification fails. */
        void onError(String message);

        /** Optional: called on the main thread with progress 0..100. */
        default void onProgress(int percent) {}
    }

    private final Context appContext;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;

    public ApkDownloader(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void start(UpdateInfo info, Callback cb) {
        cancelled = false;

        File destDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (destDir == null) {
            postError(cb, "External files dir unavailable");
            return;
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            postError(cb, "Could not create download directory");
            return;
        }
        File outFile = new File(destDir, "human-radar-" + info.versionCode + ".apk");
        if (outFile.exists() && !outFile.delete()) {
            Log.w(TAG, "Failed to delete stale APK at " + outFile);
        }

        io.execute(() -> downloadAndVerify(info, outFile, cb));
    }

    public void cancel() {
        cancelled = true;
    }

    private void downloadAndVerify(UpdateInfo info, File outFile, Callback cb) {
        HttpURLConnection conn = null;
        OutputStream out = null;
        InputStream in = null;
        try {
            URL url = new URL(info.apkUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive, */*");

            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                postError(cb, "HTTP " + code);
                return;
            }

            long total = conn.getContentLengthLong();
            if (total <= 0 && info.sizeBytes > 0) total = info.sizeBytes;
            Log.i(TAG, "Downloading " + info.apkUrl + " (" + total + " bytes) -> " + outFile);

            in  = conn.getInputStream();
            out = new FileOutputStream(outFile);

            byte[] buf = new byte[BUFFER_SIZE];
            long downloaded = 0;
            long sinceLastReport = 0;
            int lastPct = -1;
            int read;
            while ((read = in.read(buf)) > 0) {
                if (cancelled) {
                    Log.i(TAG, "Download cancelled at " + downloaded + "/" + total);
                    safeDelete(outFile);
                    postError(cb, "Download cancelled");
                    return;
                }
                out.write(buf, 0, read);
                downloaded += read;
                sinceLastReport += read;
                if (sinceLastReport >= PROGRESS_CHUNK && total > 0) {
                    sinceLastReport = 0;
                    int pct = (int) Math.min(100, downloaded * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        final int finalPct = pct;
                        main.post(() -> cb.onProgress(finalPct));
                    }
                }
            }
            out.flush();
            out.close();
            out = null;
            in.close();
            in = null;

            if (cancelled) {
                safeDelete(outFile);
                postError(cb, "Download cancelled");
                return;
            }

            // Final 100% tick so the UI never freezes at 99%
            main.post(() -> cb.onProgress(100));

            if (info.sizeBytes > 0 && Math.abs(outFile.length() - info.sizeBytes) > 0) {
                Log.w(TAG, "Size mismatch: got " + outFile.length()
                        + " expected " + info.sizeBytes);
            }

            String actualSha = sha256(outFile);
            if (info.sha256 != null && !info.sha256.isEmpty()
                    && !actualSha.equalsIgnoreCase(info.sha256)) {
                Log.w(TAG, "SHA-256 mismatch: actual=" + actualSha + " expected=" + info.sha256);
                safeDelete(outFile);
                postError(cb, "Checksum mismatch — file rejected");
                return;
            }

            Log.i(TAG, "APK ready: " + outFile + " (" + outFile.length() + " bytes)");
            main.post(() -> cb.onReady(outFile));

        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            safeDelete(outFile);
            postError(cb, e.getMessage());
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private void postError(Callback cb, String msg) {
        main.post(() -> cb.onError(msg != null ? msg : "Unknown error"));
    }

    private static void safeDelete(File f) {
        if (f != null && f.exists() && !f.delete()) {
            Log.w(TAG, "Could not delete partial APK at " + f);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }

    static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
