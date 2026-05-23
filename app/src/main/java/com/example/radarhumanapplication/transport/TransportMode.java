package com.example.radarhumanapplication.transport;

/**
 * How radar frames should reach the app.
 *
 * <p>Use case driver: the hardware is mounted in a fixed location while the
 * user walks around with their phone. Sometimes they're on the same WiFi as
 * the ESP32 (LAN reachable, very low latency over WebSocket); other times
 * they're out of range and must fall back to the cloud MQTT broker.
 */
public enum TransportMode {

    /** MQTT broker only. Works anywhere with internet. Higher latency
     *  (~80–200 ms) and depends on the broker being available. */
    CLOUD,

    /** Direct WebSocket to each ESP32 on the LAN. ~5–20 ms latency, no
     *  internet required, but the phone must be on the same WiFi. */
    LAN,

    /** Default. Prefer LAN WebSocket when the device is reachable; fall
     *  back to MQTT cloud automatically when out of range or the WS times
     *  out. The user doesn't have to switch modes manually as they roam. */
    HYBRID;

    public String displayName() {
        switch (this) {
            case CLOUD:  return "Cloud (MQTT)";
            case LAN:    return "LAN direct (WebSocket)";
            case HYBRID: return "Hybrid (recommended)";
            default:     return name();
        }
    }

    public String description() {
        switch (this) {
            case CLOUD:
                return "Send every frame through the MQTT broker. Works from "
                        + "anywhere with internet, but adds 80–200 ms per frame "
                        + "and depends on the broker being online.";
            case LAN:
                return "Open a WebSocket straight to each ESP32 on the same "
                        + "WiFi. ~5–20 ms latency and zero broker cost, but "
                        + "frames stop the moment you walk out of range.";
            case HYBRID:
                return "Best of both worlds. Uses LAN WebSocket when the "
                        + "device is reachable on the local network, then "
                        + "switches to MQTT cloud automatically as you move "
                        + "out of range. Recommended for everyday use.";
            default:
                return "";
        }
    }

    public static TransportMode safeValueOf(String name, TransportMode fallback) {
        if (name == null || name.isEmpty()) return fallback;
        try {
            return TransportMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
