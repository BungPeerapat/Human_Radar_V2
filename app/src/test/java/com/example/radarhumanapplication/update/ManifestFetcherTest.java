package com.example.radarhumanapplication.update;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ManifestFetcherTest {
    private static final String PRIMARY = "https://github.com/BungPeerapat/Human_Radar_V2/releases/latest/download/";
    private static final String MIRROR = "https://systemdeveloper.ddns.net/human-radar/updates/";
    private static final String APP = "{\"versionCode\":104,\"versionName\":\"1.1.5\",\"apkUrl\":\"https://example.com/app.apk\",\"sha256\":\"" + "a".repeat(64) + "\"}";
    private static final String FW = "{\"versionCode\":104,\"versionName\":\"1.1.5\",\"binUrl\":\"https://example.com/fw.bin\",\"sizeBytes\":1024,\"sha256\":\"" + "a".repeat(64) + "\"}";

    @Test public void gatewayTimeoutFallsBackToValidatedProjectMirror() throws Exception {
        List<String> calls = new ArrayList<>();
        ManifestFetcher client = new ManifestFetcher(url -> {
            calls.add(url);
            if (url.startsWith(PRIMARY)) throw new IOException("HTTP 504");
            return APP;
        });
        assertEquals(104, client.app(PRIMARY + "update.json").versionCode);
        assertEquals(List.of(PRIMARY + "update.json", MIRROR + "update.json"), calls);
    }

    @Test public void healthyPrimaryDoesNotRequestMirror() throws Exception {
        List<String> calls = new ArrayList<>();
        assertEquals(104, new ManifestFetcher(url -> { calls.add(url); return APP; })
                .app(PRIMARY + "update.json").versionCode);
        assertEquals(1, calls.size());
    }

    @Test public void malformedPrimaryCanUseValidMirror() throws Exception {
        assertEquals(104, new ManifestFetcher(url -> url.startsWith(PRIMARY) ? "<html>bad gateway</html>" : APP)
                .app(PRIMARY + "update.json").versionCode);
    }

    @Test public void customUpdateChannelIsNeverSilentlyChanged() {
        List<String> calls = new ArrayList<>();
        try {
            new ManifestFetcher(url -> { calls.add(url); throw new IOException("HTTP 504"); })
                    .app("https://custom.example/update.json");
            fail();
        } catch (IOException expected) { assertEquals(1, calls.size()); }
    }

    @Test public void firmwareAndBridgeChecksUseTheirOwnMirrors() throws Exception {
        List<String> calls = new ArrayList<>();
        ManifestFetcher client = new ManifestFetcher(url -> {
            calls.add(url);
            if (url.startsWith(PRIMARY)) throw new IOException("HTTP 504");
            return FW;
        });
        assertTrue(client.firmware(PRIMARY + "firmware.json").isUsable());
        assertTrue(client.firmware(PRIMARY + "bridge.json").isUsable());
        assertEquals(MIRROR + "firmware.json", calls.get(1));
        assertEquals(MIRROR + "bridge.json", calls.get(3));
    }

    @Test public void invalidMirrorMustNotBeAcceptedAsAnUpdate() {
        try {
            new ManifestFetcher(url -> { if (url.startsWith(PRIMARY)) throw new IOException("HTTP 504"); return "{}"; })
                    .app(PRIMARY + "update.json");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Primary"));
            assertTrue(expected.getMessage().contains("mirror"));
        }
    }

    @Test public void apkMirrorIsLimitedToProjectReleaseFiles() {
        assertEquals(MIRROR + "latest.apk", ManifestFetcher.apkMirror(
                "https://github.com/BungPeerapat/Human_Radar_V2/releases/download/v1.1.5/human-radar-v1.1.5.apk"));
        assertNull(ManifestFetcher.apkMirror("https://custom.example/app.apk"));
        assertNull(ManifestFetcher.apkMirror("https://github.com/other/repo/releases/download/v1.1.5/app.apk"));
    }
}
