package com.example.radarhumanapplication.update;

import com.google.gson.Gson;
import java.io.IOException;

/** Validates each source before accepting it; custom channels never fall back to production. */
final class ManifestFetcher {
    private static final String PRIMARY =
            "https://github.com/BungPeerapat/Human_Radar_V2/releases/latest/download/";
    private static final String MIRROR =
            "https://systemdeveloper.ddns.net/human-radar/updates/";
    interface Transport { String get(String url) throws IOException; }
    private interface Parser<T> { T parse(String json) throws IOException; }
    private final Transport transport;
    private final Gson gson = new Gson();

    ManifestFetcher(Transport transport) { this.transport = transport; }

    static String apkMirror(String url) {
        return url != null && url.matches("https://github\\.com/BungPeerapat/Human_Radar_V2/releases/download/v"
                + "([0-9]+\\.[0-9]+\\.[0-9]+)/human-radar-v\\1\\.apk") ? MIRROR + "latest.apk" : null;
    }

    UpdateInfo app(String url) throws IOException {
        return fetch(url, "update.json", json -> {
            UpdateInfo info = gson.fromJson(json, UpdateInfo.class);
            if (info == null || !info.isUsable()) throw new IOException("App manifest is malformed");
            return info;
        });
    }

    FirmwareManifest firmware(String url) throws IOException {
        String name = url.equals(PRIMARY + "bridge.json") ? "bridge.json" : "firmware.json";
        return fetch(url, name, json -> {
            FirmwareManifest info = gson.fromJson(json, FirmwareManifest.class);
            if (info == null || !info.isUsable()) throw new IOException("Firmware manifest is malformed");
            return info;
        });
    }

    private <T> T fetch(String url, String name, Parser<T> parser) throws IOException {
        IOException primary;
        try { return parseSource(url, parser); }
        catch (IOException failure) { primary = failure; }
        if (!url.equals(PRIMARY + name)) throw primary;
        try { return parseSource(MIRROR + name, parser); }
        catch (IOException failure) {
            IOException combined = new IOException("Primary: " + primary.getMessage()
                    + "; mirror: " + failure.getMessage(), failure);
            combined.addSuppressed(primary);
            throw combined;
        }
    }

    private <T> T parseSource(String url, Parser<T> parser) throws IOException {
        try { return parser.parse(transport.get(url)); }
        catch (RuntimeException invalidJson) { throw new IOException("Manifest is malformed", invalidJson); }
    }
}
