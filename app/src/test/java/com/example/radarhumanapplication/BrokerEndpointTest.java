package com.example.radarhumanapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class BrokerEndpointTest {
    @Test public void projectDefaultsRequireTlsAndUseHostname() {
        assertEquals("systemdeveloper.ddns.net", BrokerEndpoint.DEFAULT_HOST);
        assertEquals(8883, BrokerEndpoint.DEFAULT_PORT);
        assertEquals("humanradar", BrokerEndpoint.DEFAULT_USERNAME);
        assertTrue(BrokerEndpoint.usesTls(BrokerEndpoint.DEFAULT_HOST, 8883));
        assertTrue(BrokerEndpoint.usesTls(BrokerEndpoint.DEFAULT_HOST, 9002));
        assertTrue(BrokerEndpoint.usesWebSocket(9002));
    }

    @Test public void legacyConnectionRemainsAvailableForExplicitMigration() {
        assertFalse(BrokerEndpoint.usesTls("119.59.99.155", 8883));
        assertFalse(BrokerEndpoint.usesWebSocket(8883));
        assertFalse(BrokerEndpoint.usesTls("192.168.1.10", 1883));
        assertTrue(BrokerEndpoint.usesTls("custom.example", 8883));
    }

    @Test public void projectEndpointRejectsAccidentalPlaintextPorts() {
        assertFalse(BrokerEndpoint.isValid("systemdeveloper.ddns.net", 1883));
        assertFalse(BrokerEndpoint.isValid("203.159.94.20", 8883));
        assertTrue(BrokerEndpoint.isValid("systemdeveloper.ddns.net", 9002));
        assertTrue(BrokerEndpoint.isValid("custom.example", 1883));
        assertFalse(BrokerEndpoint.isValid("", 8883));
        assertFalse(BrokerEndpoint.isValid("custom.example", 0));
        assertFalse(BrokerEndpoint.isValid("custom.example", 65536));
    }
}
