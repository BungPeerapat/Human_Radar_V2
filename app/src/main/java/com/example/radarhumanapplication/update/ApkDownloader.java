package com.example.radarhumanapplication.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wraps Android's {@link DownloadManager} to fetch the APK and verify its SHA-256 against the
 * value declared in the manifest. The downloaded file lives in the app's external files dir,
 * which means no storage permission is required and uninstalling the app cleans it up.
 */
public class ApkDownloader {

    private static final String TAG = "ApkDownloader";

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

    private long currentDownloadId = -1;
    private BroadcastReceiver completionReceiver;
    private volatile boolean stopped = false;
    private int lastReportedPct = -1;

    public ApkDownloader(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void start(UpdateInfo info, Callback cb) {
        stopped = false;
        lastReportedPct = -1;
        File destDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (destDir == null) {
            cb.onError("External files dir unavailable");
            return;
        }
        File outFile = new File(destDir, "human-radar-" + info.versionCode + ".apk");
        if (outFile.exists() && !outFile.delete()) {
            Log.w(TAG, "Failed to delete stale APK at " + outFile);
        }

        DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            cb.onError("DownloadManager unavailable");
            return;
        }

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Human Radar update")
                .setDescription("Downloading v" + info.versionName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(outFile))
                .setMimeType("application/vnd.android.package-archive");

        currentDownloadId = dm.enqueue(req);
        registerCompletion(dm, info, outFile, cb);
        pollProgress(dm, cb);
    }

    private void registerCompletion(DownloadManager dm, UpdateInfo info, File outFile, Callback cb) {
        completionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != currentDownloadId) return;
                unregisterCompletion();

                io.execute(() -> {
                    try {
                        if (!queryStatusSucceeded(dm, id)) {
                            postError(cb, "Download failed");
                            return;
                        }
                        if (!outFile.exists() || outFile.length() == 0) {
                            postError(cb, "Downloaded file missing");
                            return;
                        }
                        if (info.sizeBytes > 0 && Math.abs(outFile.length() - info.sizeBytes) > 0) {
                            // Size mismatch is suspicious but not always fatal (server may strip
                            // padding). The SHA-256 check below is the real gate.
                            Log.w(TAG, "Size mismatch: got " + outFile.length() + " expected " + info.sizeBytes);
                        }
                        String actualSha = sha256(outFile);
                        if (!actualSha.equalsIgnoreCase(info.sha256)) {
                            //noinspection ResultOfMethodCallIgnored
                            outFile.delete();
                            postError(cb, "Checksum mismatch — file rejected");
                            return;
                        }
                        main.post(() -> cb.onReady(outFile));
                    } catch (Exception e) {
                        Log.e(TAG, "Post-download verification failed", e);
                        postError(cb, e.getMessage());
                    }
                });
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(completionReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(completionReceiver, filter);
        }
    }

    private void unregisterCompletion() {
        if (completionReceiver != null) {
            try {
                appContext.unregisterReceiver(completionReceiver);
            } catch (IllegalArgumentException ignored) {}
            completionReceiver = null;
        }
    }

    private void pollProgress(DownloadManager dm, Callback cb) {
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (stopped || currentDownloadId == -1 || completionReceiver == null) return;
                try {
                    DownloadManager.Query q = new DownloadManager.Query().setFilterById(currentDownloadId);
                    try (Cursor c = dm.query(q)) {
                        if (c != null && c.moveToFirst()) {
                            int statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                            int totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                            int doneCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                            int status = statusCol >= 0 ? c.getInt(statusCol) : 0;
                            long total = totalCol >= 0 ? c.getLong(totalCol) : 0;
                            long done = doneCol >= 0 ? c.getLong(doneCol) : 0;
                            if (total > 0) {
                                int pct = (int) (done * 100 / total);
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct;
                                    try {
                                        cb.onProgress(pct);
                                    } catch (Exception e) {
                                        // Never let a UI callback failure kill the polling loop
                                        Log.w(TAG, "onProgress callback threw", e);
                                    }
                                }
                            }
                            if (status == DownloadManager.STATUS_SUCCESSFUL
                                    || status == DownloadManager.STATUS_FAILED) {
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Progress poll failed", e);
                }
                if (!stopped) {
                    main.postDelayed(this, 500);
                }
            }
        };
        main.postDelayed(poll, 500);
    }

    private boolean queryStatusSucceeded(DownloadManager dm, long id) {
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(q)) {
            if (c == null || !c.moveToFirst()) return false;
            int idx = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return idx >= 0 && c.getInt(idx) == DownloadManager.STATUS_SUCCESSFUL;
        }
    }

    private void postError(Callback cb, String msg) {
        main.post(() -> cb.onError(msg != null ? msg : "Unknown error"));
    }

    public void cancel() {
        stopped = true;
        if (currentDownloadId != -1) {
            DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                try { dm.remove(currentDownloadId); } catch (Exception e) {
                    Log.w(TAG, "dm.remove failed", e);
                }
            }
            currentDownloadId = -1;
        }
        unregisterCompletion();
    }

    static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
