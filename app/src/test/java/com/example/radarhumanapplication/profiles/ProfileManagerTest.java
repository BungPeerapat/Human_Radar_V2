package com.example.radarhumanapplication.profiles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class ProfileManagerTest {

    static class InMemoryStore implements ProfileStore {
        String json;
        String activeId;
        @Override public String readProfilesJson() { return json; }
        @Override public void writeProfilesJson(String json) { this.json = json; }
        @Override public String readActiveId() { return activeId; }
        @Override public void writeActiveId(String id) { this.activeId = id; }
    }

    private ProfileManager mgr;
    private InMemoryStore store;

    @Before
    public void setUp() {
        mgr = ProfileManager.getInstance();
        mgr.resetForTest();
        store = new InMemoryStore();
        mgr.attach(store);
    }

    @After
    public void tearDown() {
        mgr.resetForTest();
    }

    @Test
    public void emptyByDefault() {
        assertTrue(mgr.getProfiles().isEmpty());
        assertNull(mgr.getActive());
    }

    @Test
    public void addAndPersist() {
        ConnectionProfile p = ConnectionProfile.create("Home", "192.168.1.10", 1883,
                "HumanRadar", "u", "pw");
        mgr.addProfile(p);
        List<ConnectionProfile> list = mgr.getProfiles();
        assertEquals(1, list.size());
        assertEquals("Home", list.get(0).name);
        assertNotNull(store.json);
        assertTrue(store.json.contains("Home"));
    }

    @Test
    public void updateExistingProfile() {
        ConnectionProfile p = ConnectionProfile.create("Home", "h1", 1, "d", "u", "p");
        mgr.addProfile(p);
        p.brokerHost = "h2";
        p.brokerPort = 8883;
        assertTrue(mgr.updateProfile(p));
        ConnectionProfile updated = mgr.findById(p.id);
        assertEquals("h2", updated.brokerHost);
        assertEquals(8883, updated.brokerPort);
    }

    @Test
    public void updateMissingProfileReturnsFalse() {
        ConnectionProfile p = new ConnectionProfile("does-not-exist", "x", "h", 1,
                "d", "u", "p");
        assertFalse(mgr.updateProfile(p));
    }

    @Test
    public void deleteRemovesAndClearsActive() {
        ConnectionProfile p = ConnectionProfile.create("A", "h", 1, "d", "u", "p");
        mgr.addProfile(p);
        mgr.setActive(p.id);
        assertEquals(p.id, mgr.getActiveId());

        assertTrue(mgr.deleteProfile(p.id));
        assertTrue(mgr.getProfiles().isEmpty());
        assertNull(mgr.getActiveId());
    }

    @Test
    public void setActiveRejectsUnknownId() {
        assertFalse(mgr.setActive("nope"));
    }

    @Test
    public void persistedDataReloadsAfterReattach() {
        ConnectionProfile p = ConnectionProfile.create("A", "h", 1, "d", "u", "p");
        mgr.addProfile(p);
        mgr.setActive(p.id);

        mgr.resetForTest();
        // Reuse the same store contents
        mgr.attach(store);

        assertEquals(1, mgr.getProfiles().size());
        assertEquals(p.id, mgr.getActiveId());
        assertNotNull(mgr.getActive());
        assertEquals("A", mgr.getActive().name);
    }
}
