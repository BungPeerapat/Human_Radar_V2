package com.example.radarhumanapplication.update;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Live HTTPS mirror + real Android downloader; never installs or sends commands to a device. */
@RunWith(AndroidJUnit4.class)
public class UpdateFallbackInstrumentedTest {
    @Test public void gatewayFailureUsesLiveMirrorAndDownloadsVerifiedApk() throws Exception {
        ManifestFetcher fetcher = new ManifestFetcher(url -> {
            if (url.startsWith("https://github.com/")) throw new IOException("HTTP 504");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                assertEquals(200, connection.getResponseCode());
                try (java.io.InputStream stream = connection.getInputStream()) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
                    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally { connection.disconnect(); }
        });
        UpdateInfo info = fetcher.app("https://github.com/BungPeerapat/Human_Radar_V2/releases/latest/download/update.json");
        assertTrue(info.apkUrl.startsWith("https://systemdeveloper.ddns.net/human-radar/updates/"));
        // A manifest may be cached successfully while only the GitHub APK download fails.
        // Exercise binary fallback too, using the same expected version/hash from the mirror.
        // This deliberately unpublished version keeps the test exercising fallback
        // even after GitHub recovers. The real selected version/hash remain unchanged.
        info.apkUrl = "https://github.com/BungPeerapat/Human_Radar_V2/releases/download/"
                + "v999999.0.0/human-radar-v999999.0.0.apk";
        CountDownLatch ready = new CountDownLatch(1);
        String[] error = new String[1];
        File[] downloaded = new File[1];
        ApkDownloader downloader = new ApkDownloader(InstrumentationRegistry.getInstrumentation().getTargetContext());
        downloader.start(info, new ApkDownloader.Callback() {
            @Override public void onReady(File file) { downloaded[0] = file; ready.countDown(); }
            @Override public void onError(String message) { error[0] = message; ready.countDown(); }
        });
        try {
            assertTrue("Mirror APK download must finish", ready.await(60, TimeUnit.SECONDS));
            assertNull(error[0]);
            assertNotNull(downloaded[0]);
            assertEquals(info.sizeBytes, downloaded[0].length());
        } finally {
            downloader.cancel();
            if (downloaded[0] != null) assertTrue(downloaded[0].delete());
        }
    }

    @Test public void actualManualCheckCompletesWithoutGatewayError() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        UpdateManager.Result[] result = new UpdateManager.Result[1];
        UpdateManager.getInstance().checkManual(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                value -> { result[0] = value; ready.countDown(); });
        assertTrue("Manual check must complete", ready.await(45, TimeUnit.SECONDS));
        assertNotNull(result[0]);
        assertNotEquals(result[0].errorMessage, UpdateManager.Status.ERROR, result[0].status);
    }
}
