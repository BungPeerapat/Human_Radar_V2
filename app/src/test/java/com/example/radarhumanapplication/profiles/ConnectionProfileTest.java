package com.example.radarhumanapplication.profiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class ConnectionProfileTest {

    @Test
    public void jsonRoundTripPreservesAllFields() {
        ConnectionProfile original = new ConnectionProfile(
                "id-1", "Home", "192.168.1.10", 1883,
                "HumanRadar", "alice", "pw");

        String json = original.toJson();
        assertNotNull(json);

        ConnectionProfile restored = ConnectionProfile.fromJson(json);
        assertEquals("id-1", restored.id);
        assertEquals("Home", restored.name);
        assertEquals("192.168.1.10", restored.brokerHost);
        assertEquals(1883, restored.brokerPort);
        assertEquals("HumanRadar", restored.deviceName);
        assertEquals("alice", restored.username);
        assertEquals("pw", restored.password);
        assertEquals(original, restored);
    }

    @Test
    public void createGeneratesUniqueIds() {
        ConnectionProfile a = ConnectionProfile.create("A", "h", 1, "d", "u", "p");
        ConnectionProfile b = ConnectionProfile.create("B", "h", 1, "d", "u", "p");
        assertNotNull(a.id);
        assertNotNull(b.id);
        assertNotEquals(a.id, b.id);
    }

    @Test
    public void equalsHandlesNullsAndDifferences() {
        ConnectionProfile a = new ConnectionProfile("1", "n", "h", 1, "d", null, null);
        ConnectionProfile b = new ConnectionProfile("1", "n", "h", 1, "d", null, null);
        assertEquals(a, b);

        ConnectionProfile c = new ConnectionProfile("1", "n", "h", 2, "d", null, null);
        assertNotEquals(a, c);
    }
}
