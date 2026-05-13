package com.example.radarhumanapplication;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Thin wrapper over {@link WifiManager} that returns a deduplicated, signal-sorted list
 * of SSIDs the phone can see right now. Designed for the WiFi Setup card's SSID
 * dropdown — the picker should "just work" with one tap, including async scan,
 * permission state, and Android's SDK-dependent permission split (FINE_LOCATION
 * on Android &le; 12, NEARBY_WIFI_DEVICES on Android &ge; 13).
 *
 * <p>Usage: hold one instance per fragment, call {@link #hasRequiredPermission(Context)}
 * to decide whether to ask the user for permission first, then {@link #scan(Context,
 * ScanCallback)}. The callback fires on the main thread with either a list of SSIDs
 * or an error message.</p>
 */
public final class WifiScanHelper {

    private static final String TAG = "WifiScanHelper";
    private static final long CACHE_TTL_MS = 30_000L;
    private static final long SCAN_TIMEOUT_MS = 6_000L;

    public interface ScanCallback {
        void onScanResult(List<String> ssids, String errorOrNull);
    }

    private List<String> cachedSsids = Collections.emptyList();
    private long cachedAtMs = 0;
    private BroadcastReceiver receiver;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Permission needed for scan results, picking the right one per SDK level. */
    public static String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{ Manifest.permission.NEARBY_WIFI_DEVICES };
        }
        return new String[]{ Manifest.permission.ACCESS_FINE_LOCATION };
    }

    public static boolean hasRequiredPermission(Context ctx) {
        for (String p : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(ctx, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Returns the most recent successful scan, or {@code null} if cache is stale/empty. */
    public List<String> cachedSsidsIfFresh() {
        if (System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS && !cachedSsids.isEmpty()) {
            return new ArrayList<>(cachedSsids);
        }
        return null;
    }

    /**
     * Kick off an async scan. The callback fires once with either a list or an error string.
     * Reuses a recent cached scan when available so dropdowns feel instant.
     */
    public void scan(Context ctx, ScanCallback cb) {
        if (cb == null) return;
        List<String> fresh = cachedSsidsIfFresh();
        if (fresh != null) {
            cb.onScanResult(fresh, null);
            return;
        }
        if (!hasRequiredPermission(ctx)) {
            cb.onScanResult(Collections.emptyList(), "Permission required");
            return;
        }
        WifiManager wm = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            cb.onScanResult(Collections.emptyList(), "WiFi service unavailable");
            return;
        }
        if (!wm.isWifiEnabled()) {
            cb.onScanResult(Collections.emptyList(), "WiFi is off — turn it on first");
            return;
        }
        Context appCtx = ctx.getApplicationContext();

        // Register a one-shot receiver for SCAN_RESULTS_AVAILABLE then trigger the scan.
        unregister(appCtx);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                List<String> ssids = collect(wm);
                cachedSsids = ssids;
                cachedAtMs = System.currentTimeMillis();
                unregister(appCtx);
                main.removeCallbacksAndMessages(null);
                cb.onScanResult(ssids, null);
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appCtx.registerReceiver(receiver, filter);
        }

        // Android 9+ heavily throttles startScan(); if it's throttled we fall back to
        // whatever results the system already has on hand.
        boolean started;
        try {
            started = wm.startScan();
        } catch (SecurityException se) {
            Log.w(TAG, "startScan SecurityException", se);
            started = false;
        }
        if (!started) {
            // No fresh scan available — return whatever the cache holds.
            List<String> snap = collect(wm);
            cachedSsids = snap;
            cachedAtMs = System.currentTimeMillis();
            unregister(appCtx);
            cb.onScanResult(snap, snap.isEmpty()
                    ? "Throttled by Android — try again in a moment" : null);
            return;
        }

        // Timeout — return whatever we have if the broadcast doesn't arrive.
        main.postDelayed(() -> {
            if (receiver == null) return;   // already delivered
            List<String> snap = collect(wm);
            cachedSsids = snap;
            cachedAtMs = System.currentTimeMillis();
            unregister(appCtx);
            cb.onScanResult(snap, snap.isEmpty() ? "Scan timed out" : null);
        }, SCAN_TIMEOUT_MS);
    }

    public void cancel(Context ctx) {
        unregister(ctx == null ? null : ctx.getApplicationContext());
        main.removeCallbacksAndMessages(null);
    }

    private void unregister(Context appCtx) {
        if (receiver != null && appCtx != null) {
            try { appCtx.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) {}
        }
        receiver = null;
    }

    /** Map ScanResult list to a deduplicated SSID list sorted by signal strength. */
    @SuppressWarnings("deprecation")
    private static List<String> collect(WifiManager wm) {
        List<ScanResult> raw;
        try {
            raw = wm.getScanResults();
        } catch (SecurityException se) {
            return Collections.emptyList();
        }
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        // Sort by signal strength descending — strongest networks at the top.
        java.util.Collections.sort(raw, (a, b) -> Integer.compare(b.level, a.level));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ScanResult r : raw) {
            String s = r.SSID;
            if (s == null) continue;
            // Older Android may quote SSID strings; strip and drop empties.
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            String trimmed = s.trim();
            if (trimmed.isEmpty()) continue;
            seen.add(trimmed);
        }
        return new ArrayList<>(seen);
    }

    /** Human-readable signal label e.g. "▮▮▮ -47 dBm". Optional caller use. */
    public static String labelForSsid(WifiManager wm, String ssid) {
        if (wm == null || ssid == null) return ssid;
        try {
            for (ScanResult r : wm.getScanResults()) {
                String name = r.SSID == null ? "" : r.SSID;
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                if (ssid.equals(name)) {
                    int bars = WifiManager.calculateSignalLevel(r.level, 4);
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < 4; i++) b.append(i < bars ? "▮" : "▯");
                    return String.format(Locale.US, "%s  %s  %d dBm", ssid, b, r.level);
                }
            }
        } catch (SecurityException ignored) {}
        return ssid;
    }
}
