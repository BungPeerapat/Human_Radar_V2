package com.example.radarhumanapplication.update;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MqttOtaSessionTest {
    @Test public void offlineStatusCannotVerifyAnInstalledImage() {
        assertFalse(MqttOtaSession.isOnlineVersion("2.0", "2.0", "offline"));
        assertFalse(MqttOtaSession.isOnlineVersion("2.0", "2.0", "unknown"));
        assertFalse(MqttOtaSession.isOnlineVersion("2.0", "1.0", "online"));
        assertFalse(MqttOtaSession.isOnlineVersion("2.0", "", "online"));
        assertTrue(MqttOtaSession.isOnlineVersion("2.0", "2.0", "online"));
    }
    private JsonObject ack(String id, String status, boolean restarting) {
        JsonObject ack = new JsonObject();
        ack.addProperty("request_id", id); ack.addProperty("status", status);
        ack.addProperty("restarting", restarting); return ack;
    }
    @Test public void unrelatedDevicesAndRequestsCannotCompleteAnInstall() {
        MqttOtaSession s = new MqttOtaSession("test", "request");
        assertEquals(MqttOtaSession.Event.IGNORED, s.update("other", ack("request", "ok", true)));
        assertEquals(MqttOtaSession.Event.IGNORED, s.update("test", ack("other", "ok", true)));
    }
    @Test public void startingAckDoesNotMeanImageWasInstalled() {
        MqttOtaSession s = new MqttOtaSession("test", "request");
        assertEquals(MqttOtaSession.Event.IGNORED, s.update("test", ack("request", "ok", false)));
        assertEquals(MqttOtaSession.Event.COMMITTED, s.update("test", ack("request", "ok", true)));
        assertEquals(MqttOtaSession.Event.IGNORED, s.update("test", ack("request", "ok", true)));
    }
    @Test public void rejectionIsTerminalAndRetainsDeviceReason() {
        MqttOtaSession s = new MqttOtaSession("test", "request");
        JsonObject ack = ack("request", "error", false); ack.addProperty("message", "SHA-256 mismatch");
        assertEquals(MqttOtaSession.Event.ERROR, s.update("test", ack));
        assertEquals("SHA-256 mismatch", s.getMessage());
        assertEquals(MqttOtaSession.Event.IGNORED, s.update("test", ack("request", "ok", true)));
    }
    @Test public void malformedAckDoesNotBreakTheListener() {
        MqttOtaSession s = new MqttOtaSession("test", "request");
        assertEquals(MqttOtaSession.Event.IGNORED, s.update("test", null));
        assertEquals(MqttOtaSession.Event.IGNORED, s.update("test", new JsonObject()));
        JsonObject ack = ack("request", "progress", false); ack.addProperty("pct", 999);
        assertEquals(MqttOtaSession.Event.PROGRESS, s.update("test", ack));
        assertEquals(100, s.getProgress());
    }
}
