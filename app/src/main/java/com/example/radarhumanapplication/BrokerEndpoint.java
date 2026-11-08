package com.example.radarhumanapplication;

/** Project defaults and transport policy. Saved custom endpoints remain opt-in. */
public final class BrokerEndpoint {
    public static final String DEFAULT_HOST = "systemdeveloper.ddns.net";
    public static final int DEFAULT_PORT = 8883;
    public static final String DEFAULT_USERNAME = "humanradar";

    private BrokerEndpoint() {}

    public static boolean isPrimary(String host) {
        return host != null && (DEFAULT_HOST.equalsIgnoreCase(host)
                || (DEFAULT_HOST + ".").equalsIgnoreCase(host));
    }

    public static boolean isValid(String host, int port) {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) return false;
        // IP certificates do not match the broker's DNS certificate.
        if ("203.159.94.20".equals(host)) return false;
        return !isPrimary(host) || port == DEFAULT_PORT || port == 9002;
    }

    public static boolean usesTls(String host, int port) {
        // The former project broker used plaintext on 8883. Keep explicit legacy
        // profiles usable for migration; never silently replace their credentials.
        if ("119.59.99.155".equals(host)) return false;
        return isPrimary(host) || port == 8883 || port == 9002;
    }

    public static boolean usesWebSocket(int port) { return port == 9002; }
}
