package com.example.radarhumanapplication.update;

import com.google.gson.JsonObject;

/** Correlates a device's OTA acknowledgement before reboot/version verification. */
public final class MqttOtaSession {
    public enum Event { IGNORED, PROGRESS, ERROR, COMMITTED }
    private final String device;
    private final String requestId;
    private boolean terminal;
    private String message = "";
    private int progress;

    public MqttOtaSession(String device, String requestId) {
        this.device = device;
        this.requestId = requestId;
    }

    public synchronized Event update(String sender, JsonObject ack) {
        if (terminal || !device.equals(sender) || ack == null) return Event.IGNORED;
        try {
            if (!ack.has("request_id") || !requestId.equals(ack.get("request_id").getAsString())
                    || !ack.has("status")) return Event.IGNORED;
            String status = ack.get("status").getAsString();
            if ("error".equals(status)) {
                message = ack.has("message") ? ack.get("message").getAsString() : "Device rejected OTA";
                terminal = true;
                return Event.ERROR;
            }
            if ("progress".equals(status)) {
                progress = ack.has("pct") ? Math.max(0, Math.min(100, ack.get("pct").getAsInt())) : 0;
                return Event.PROGRESS;
            }
            if ("ok".equals(status) && ack.has("restarting") && ack.get("restarting").getAsBoolean()) {
                terminal = true;
                return Event.COMMITTED;
            }
        } catch (RuntimeException malformedAck) {
            return Event.IGNORED;
        }
        return Event.IGNORED;
    }

    public synchronized String getMessage() { return message; }
    public synchronized int getProgress() { return progress; }

    public static boolean isOnlineVersion(String expected, String reported, String status) {
        return "online".equalsIgnoreCase(status) && reported != null && !reported.isEmpty()
                && (expected == null || expected.isEmpty() || expected.equals(reported));
    }
}
