package com.example.radarhumanapplication.transport;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persisted transport configuration backed by SharedPreferences.
 *
 * <p>All getters return current values; setters write immediately. The
 * {@link Listener} fan-out lets the {@link HybridTransportManager} react to
 * changes without polling.
 */
public final class TransportSettings {

    private static final String PREFS = "transport_prefs";

    // Keys
    private static final String K_ENABLED            = "enabled";
    private static final String K_MODE               = "mode";
    private static final String K_MDNS_ENABLED       = "mdns_enabled";
    private static final String K_PREFER_LAN_ON_WIFI = "prefer_lan_on_wifi";
    private static final String K_SHOW_BADGE         = "show_transport_badge";
    private static final String K_VERBOSE_LOG        = "verbose_log";
    private static final String K_DISCOVERY_TIMEOUT  = "discovery_timeout_ms";
    private static final String K_WS_RECONNECT       = "ws_reconnect_ms";
    private static final String K_FALLBACK_GRACE     = "fallback_grace_ms";
    private static final String K_TCP_PROBE_TIMEOUT  = "tcp_probe_timeout_ms";
    private static final String K_MANUAL_IPS         = "manual_direct_ips";

    // Defaults
    public  static final boolean DEFAULT_ENABLED              = true;
    public  static final TransportMode DEFAULT_MODE           = TransportMode.HYBRID;
    public  static final boolean DEFAULT_MDNS_ENABLED         = true;
    public  static final boolean DEFAULT_PREFER_LAN_ON_WIFI   = true;
    public  static final boolean DEFAULT_SHOW_BADGE           = true;
    public  static final boolean DEFAULT_VERBOSE_LOG          = false;
    public  static final int     DEFAULT_DISCOVERY_TIMEOUT_MS = 2_000;
    public  static final int     DEFAULT_WS_RECONNECT_MS      = 3_000;
    public  static final int     DEFAULT_FALLBACK_GRACE_MS    = 5_000;
    public  static final int     DEFAULT_TCP_PROBE_TIMEOUT_MS = 1_500;

    public interface Listener {
        void onTransportSettingsChanged(TransportSettings settings);
    }

    private static volatile TransportSettings instance;

    public static TransportSettings get(Context ctx) {
        TransportSettings local = instance;
        if (local != null) return local;
        synchronized (TransportSettings.class) {
            if (instance == null) instance = new TransportSettings(ctx.getApplicationContext());
            return instance;
        }
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private TransportSettings(Context appContext) {
        this.appContext = appContext;
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void addListener(Listener l)    { if (l != null) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyListeners() {
        for (Listener l : listeners) {
            try { l.onTransportSettingsChanged(this); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------
    //  Master switch
    // ------------------------------------------------------------------
    public boolean isEnabled() {
        return prefs.getBoolean(K_ENABLED, DEFAULT_ENABLED);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(K_ENABLED, enabled).apply();
        notifyListeners();
    }

    // ------------------------------------------------------------------
    //  Mode
    // ------------------------------------------------------------------
    public TransportMode getMode() {
        return TransportMode.safeValueOf(prefs.getString(K_MODE, DEFAULT_MODE.name()),
                DEFAULT_MODE);
    }

    public void setMode(TransportMode mode) {
        if (mode == null) return;
        prefs.edit().putString(K_MODE, mode.name()).apply();
        notifyListeners();
    }

    // ------------------------------------------------------------------
    //  Discovery + reconnect tuning
    // ------------------------------------------------------------------
    public boolean isMdnsEnabled() {
        return prefs.getBoolean(K_MDNS_ENABLED, DEFAULT_MDNS_ENABLED);
    }
    public void setMdnsEnabled(boolean v) {
        prefs.edit().putBoolean(K_MDNS_ENABLED, v).apply();
        notifyListeners();
    }

    public boolean isPreferLanOnSameWifi() {
        return prefs.getBoolean(K_PREFER_LAN_ON_WIFI, DEFAULT_PREFER_LAN_ON_WIFI);
    }
    public void setPreferLanOnSameWifi(boolean v) {
        prefs.edit().putBoolean(K_PREFER_LAN_ON_WIFI, v).apply();
        notifyListeners();
    }

    public boolean isShowBadge() {
        return prefs.getBoolean(K_SHOW_BADGE, DEFAULT_SHOW_BADGE);
    }
    public void setShowBadge(boolean v) {
        prefs.edit().putBoolean(K_SHOW_BADGE, v).apply();
        notifyListeners();
    }

    public boolean isVerboseLog() {
        return prefs.getBoolean(K_VERBOSE_LOG, DEFAULT_VERBOSE_LOG);
    }
    public void setVerboseLog(boolean v) {
        prefs.edit().putBoolean(K_VERBOSE_LOG, v).apply();
        notifyListeners();
    }

    public int getDiscoveryTimeoutMs() {
        return prefs.getInt(K_DISCOVERY_TIMEOUT, DEFAULT_DISCOVERY_TIMEOUT_MS);
    }
    public void setDiscoveryTimeoutMs(int ms) {
        prefs.edit().putInt(K_DISCOVERY_TIMEOUT, Math.max(500, ms)).apply();
        notifyListeners();
    }

    public int getWsReconnectMs() {
        return prefs.getInt(K_WS_RECONNECT, DEFAULT_WS_RECONNECT_MS);
    }
    public void setWsReconnectMs(int ms) {
        prefs.edit().putInt(K_WS_RECONNECT, Math.max(500, ms)).apply();
        notifyListeners();
    }

    public int getFallbackGraceMs() {
        return prefs.getInt(K_FALLBACK_GRACE, DEFAULT_FALLBACK_GRACE_MS);
    }
    public void setFallbackGraceMs(int ms) {
        prefs.edit().putInt(K_FALLBACK_GRACE, Math.max(500, ms)).apply();
        notifyListeners();
    }

    public int getTcpProbeTimeoutMs() {
        return prefs.getInt(K_TCP_PROBE_TIMEOUT, DEFAULT_TCP_PROBE_TIMEOUT_MS);
    }
    public void setTcpProbeTimeoutMs(int ms) {
        prefs.edit().putInt(K_TCP_PROBE_TIMEOUT, Math.max(200, ms)).apply();
        notifyListeners();
    }

    // ------------------------------------------------------------------
    //  Manually-entered direct IPs (in addition to ones discovered via
    //  MQTT /info or mDNS). Useful when broker is unreachable.
    // ------------------------------------------------------------------
    public Set<String> getManualDirectIps() {
        Set<String> stored = prefs.getStringSet(K_MANUAL_IPS, Collections.emptySet());
        return stored == null ? Collections.emptySet() : new LinkedHashSet<>(stored);
    }

    public void setManualDirectIps(Set<String> ips) {
        Set<String> copy = ips == null ? Collections.emptySet() : new HashSet<>(ips);
        prefs.edit().putStringSet(K_MANUAL_IPS, copy).apply();
        notifyListeners();
    }

    public void addManualDirectIp(String ip) {
        if (ip == null || ip.isEmpty()) return;
        Set<String> next = new LinkedHashSet<>(getManualDirectIps());
        next.add(ip);
        setManualDirectIps(next);
    }

    public void removeManualDirectIp(String ip) {
        Set<String> next = new LinkedHashSet<>(getManualDirectIps());
        if (next.remove(ip)) setManualDirectIps(next);
    }
}
