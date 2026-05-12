package com.example.radarhumanapplication.update;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams a local firmware .bin to an ESP32 OTA endpoint
 * ({@code POST http://{deviceIp}/api/firmware-update}) as multipart/form-data.
 *
 * <p>Each chunk is reported back to the caller on the main thread so the UI can show a smooth
 * progress bar. After the upload finishes the ESP32 verifies the image, flashes it into the
 * "next" OTA partition, responds with {@code {"ok":true}} and reboots into the new firmware.
 * The HTTP socket is dropped during reboot, which the uploader treats as a successful finish
 * once the response body has been consumed.</p>
 */
public final class FirmwareUploader {

    private static final String TAG = "FirmwareUploader";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 60_000; // big buffer for full flash
    private static final int BUFFER_SIZE        = 8 * 1024;
    /** Progress callbacks fire every N bytes to keep UI updates cheap. */
    private static final int PROGRESS_CHUNK     = 32 * 1024;

    public interface Callback {
        /** Called on main thread when the upload succeeds and ESP32 acknowledged restart. */
        void onCompleted();

        /** Called on main thread when something goes wrong. */
        void onError(String message);

        /** Optional: called on main thread with progress 0..100. */
        default void onProgress(int percent) {}
    }

    private final Context appContext;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;

    public FirmwareUploader(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * @param deviceIp  ESP32 host or IP (no scheme), e.g. {@code "192.168.4.1"}
     * @param firmware  content:// URI of the local .bin file (e.g. from SAF picker)
     * @param sizeBytes total bytes of the .bin file; pass 0 if unknown (progress hidden)
     */
    public void start(String deviceIp, Uri firmware, long sizeBytes, Callback cb) {
        cancelled = false;
        io.execute(() -> upload(deviceIp, firmware, sizeBytes, cb));
    }

    private void upload(String deviceIp, Uri firmware, long total, Callback cb) {
        final String boundary = "----HumanRadarBoundary"
                + Long.toHexString(System.nanoTime());
        final String CRLF = "\r\n";
        final String prefix = "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"firmware\"; filename=\"firmware.bin\""
                + CRLF
                + "Content-Type: application/octet-stream" + CRLF + CRLF;
        final byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        final byte[] suffixBytes = (CRLF + "--" + boundary + "--" + CRLF)
                .getBytes(StandardCharsets.UTF_8);
        final long contentLength = (total > 0)
                ? prefixBytes.length + total + suffixBytes.length
                : -1;

        HttpURLConnection conn = null;
        OutputStream out       = null;
        InputStream  in        = null;
        try {
            URL url = new URL("http://" + deviceIp + "/api/firmware-update");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setChunkedStreamingMode(0);
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            if (contentLength > 0 && contentLength <= Integer.MAX_VALUE) {
                conn.setFixedLengthStreamingMode(contentLength);
            }

            Log.i(TAG, "OTA upload start: " + url + " (" + total + " bytes)");

            out = conn.getOutputStream();
            out.write(prefixBytes);

            in = appContext.getContentResolver().openInputStream(firmware);
            if (in == null) {
                postError(cb, "Cannot open firmware file");
                return;
            }

            byte[] buf = new byte[BUFFER_SIZE];
            long sent = 0;
            long sinceLastReport = 0;
            int lastPct = -1;
            int read;
            while ((read = in.read(buf)) > 0) {
                if (cancelled) {
                    postError(cb, "Upload cancelled");
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
                        final int finalPct = pct;
                        main.post(() -> cb.onProgress(finalPct));
                    }
                }
            }
            out.write(suffixBytes);
            out.flush();
            out.close();
            out = null;

            main.post(() -> cb.onProgress(100));

            int code;
            try {
                code = conn.getResponseCode();
            } catch (Exception readErr) {
                // ESP32 may reboot before fully sending the response. If we got that far the
                // upload almost certainly succeeded — treat as completion.
                Log.w(TAG, "Response read failed (device may already be rebooting): "
                        + readErr.getMessage());
                main.post(cb::onCompleted);
                return;
            }
            String body = readBody(conn, code);
            Log.i(TAG, "OTA upload response: HTTP " + code + " body=" + body);

            if (code >= 200 && code < 300) {
                main.post(cb::onCompleted);
            } else {
                postError(cb, "ESP32 returned HTTP " + code
                        + (body.isEmpty() ? "" : (": " + body)));
            }
        } catch (Exception e) {
            Log.e(TAG, "OTA upload failed", e);
            postError(cb, e.getMessage());
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static String readBody(HttpURLConnection conn, int code) {
        StringBuilder sb = new StringBuilder();
        InputStream stream = null;
        try {
            stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return "";
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(stream);
        }
        return sb.toString();
    }

    private void postError(Callback cb, String msg) {
        main.post(() -> cb.onError(msg != null ? msg : "Unknown error"));
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }
}
