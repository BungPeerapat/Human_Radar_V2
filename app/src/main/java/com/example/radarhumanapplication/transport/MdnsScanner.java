package com.example.radarhumanapplication.transport;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auto-discovers ESP32 devices on the LAN by scanning for the
 * {@code _humanradar._tcp} mDNS service advertised by the firmware. Each
 * resolved device is fed back to the caller as
 * {@code (deviceName, ip, port, fw)} — the HybridTransportManager merges
 * these into its known-IP map so direct LAN WebSocket connections work
 * even when the MQTT broker is unreachable.
 *
 * <p>Lifecycle is tied to the calling fragment / activity via
 * {@link #start()} / {@link #stop()}. The class uses Android's built-in
 * {@link NsdManager} — no extra dependency required.
 */
public final class MdnsScanner {

    public interface Listener {
        /** Fired on the main thread when a device's mDNS record resolves. */
        void onMdnsDeviceResolved(String deviceName, String ip, int port,
                                  String fw, Map<String, String> txt);
        /** Fired when a previously-seen device's mDNS advertisement expires. */
        void onMdnsDeviceLost(String serviceName);
    }

    private static final String TAG          = "MdnsScanner";
    /** Matches the firmware's {@code MDNS.addService("humanradar","tcp",81)}. */
    private static final String SERVICE_TYPE = "_humanradar._tcp.";

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private NsdManager.DiscoveryListener discoveryListener;
    /** serviceName -> last resolved info, so a Lost event can fire a clean
     *  "remove this from the manager" event downstream. */
    private final Map<String, NsdServiceInfo> resolved = new LinkedHashMap<>();
    private volatile boolean running = false;

    public MdnsScanner(Context ctx, Listener listener) {
        this.nsd = (NsdManager) ctx.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public synchronized void start() {
        if (running || nsd == null) return;
        running = true;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "Discovery started for " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped for " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Discovery start failed: " + errorCode);
                running = false;
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "Discovery stop failed: " + errorCode);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service found: " + serviceInfo.getServiceName());
                // Must resolve to get IP + port + TXT. NSD requires one
                // resolve at a time per the docs — the per-resolution
                // listener is single-use so we instantiate anew.
                nsd.resolveService(serviceInfo, new ResolveListener());
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String name = serviceInfo.getServiceName();
                Log.i(TAG, "Service lost: " + name);
                resolved.remove(name);
                if (listener != null) {
                    main.post(() -> listener.onMdnsDeviceLost(name));
                }
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.w(TAG, "discoverServices threw", e);
            running = false;
        }
    }

    public synchronized void stop() {
        if (!running || nsd == null || discoveryListener == null) return;
        try { nsd.stopServiceDiscovery(discoveryListener); }
        catch (Exception ignored) {}
        discoveryListener = null;
        running = false;
        resolved.clear();
    }

    public boolean isRunning() { return running; }

    // ---------- per-service resolve callback ----------
    private final class ResolveListener implements NsdManager.ResolveListener {
        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.w(TAG, "Resolve failed for " + serviceInfo.getServiceName()
                    + " err=" + errorCode);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            InetAddress addr = serviceInfo.getHost();
            if (addr == null) return;
            String ip = addr.getHostAddress();
            int port  = serviceInfo.getPort();
            // Prefer the TXT-record "name" field — that's the original
            // mixed-case device name. NSD service name may be sanitized.
            Map<String, String> txt = parseTxt(serviceInfo);
            String name = txt.getOrDefault("name", serviceInfo.getServiceName());
            String fw   = txt.getOrDefault("fw",   "");
            resolved.put(serviceInfo.getServiceName(), serviceInfo);
            if (listener != null) {
                main.post(() ->
                        listener.onMdnsDeviceResolved(name, ip, port, fw, txt));
            }
        }
    }

    private static Map<String, String> parseTxt(NsdServiceInfo info) {
        Map<String, String> out = new HashMap<>();
        Map<String, byte[]> attrs = info.getAttributes();
        if (attrs == null) return out;
        for (Map.Entry<String, byte[]> e : attrs.entrySet()) {
            byte[] v = e.getValue();
            out.put(e.getKey(),
                    v == null ? "" : new String(v, StandardCharsets.UTF_8));
        }
        return out;
    }
}
