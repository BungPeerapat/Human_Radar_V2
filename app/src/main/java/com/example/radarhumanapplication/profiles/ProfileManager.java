package com.example.radarhumanapplication.profiles;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Singleton owner of {@link ConnectionProfile}s. Persists via {@link ProfileStore} so the core
 * add/update/delete/active-tracking logic is unit-testable without Android.
 */
public class ProfileManager {

    private static final ProfileManager INSTANCE = new ProfileManager();
    public static ProfileManager getInstance() { return INSTANCE; }

    private final Gson gson = new Gson();
    private final List<ConnectionProfile> profiles = new ArrayList<>();
    private String activeId;
    private ProfileStore store;
    private boolean attached;

    private ProfileManager() {}

    /** Production attach: backed by SharedPreferences. */
    public synchronized void attach(Context ctx) {
        if (attached) return;
        attach(new SharedPreferencesProfileStore(ctx));
    }

    /** Test-friendly attach: any {@link ProfileStore}. */
    public synchronized void attach(ProfileStore store) {
        this.store = store;
        load();
        attached = true;
    }

    /** Test reset — clears in-memory state. */
    public synchronized void resetForTest() {
        profiles.clear();
        activeId = null;
        store = null;
        attached = false;
    }

    private void load() {
        profiles.clear();
        activeId = null;
        if (store == null) return;
        String json = store.readProfilesJson();
        if (json != null) {
            try {
                Type t = new TypeToken<ArrayList<ConnectionProfile>>(){}.getType();
                List<ConnectionProfile> loaded = gson.fromJson(json, t);
                if (loaded != null) profiles.addAll(loaded);
            } catch (Exception ignored) {}
        }
        activeId = store.readActiveId();
    }

    private void persist() {
        if (store == null) return;
        store.writeProfilesJson(gson.toJson(profiles));
        store.writeActiveId(activeId);
    }

    public synchronized List<ConnectionProfile> getProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    public synchronized ConnectionProfile findById(String id) {
        if (id == null) return null;
        for (ConnectionProfile p : profiles) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public synchronized void addProfile(ConnectionProfile p) {
        if (p == null) return;
        if (p.id == null || p.id.isEmpty()) {
            p.id = java.util.UUID.randomUUID().toString();
        }
        // Reject duplicate ids
        if (findById(p.id) != null) {
            updateProfile(p);
            return;
        }
        profiles.add(p);
        persist();
    }

    public synchronized boolean updateProfile(ConnectionProfile p) {
        if (p == null || p.id == null) return false;
        for (int i = 0; i < profiles.size(); i++) {
            if (p.id.equals(profiles.get(i).id)) {
                profiles.set(i, p);
                persist();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean deleteProfile(String id) {
        if (id == null) return false;
        boolean removed = false;
        for (int i = profiles.size() - 1; i >= 0; i--) {
            if (id.equals(profiles.get(i).id)) {
                profiles.remove(i);
                removed = true;
            }
        }
        if (removed) {
            if (id.equals(activeId)) activeId = null;
            persist();
        }
        return removed;
    }

    public synchronized boolean setActive(String id) {
        if (id == null) {
            activeId = null;
            persist();
            return true;
        }
        if (findById(id) == null) return false;
        activeId = id;
        persist();
        return true;
    }

    public synchronized String getActiveId() { return activeId; }

    public synchronized ConnectionProfile getActive() {
        return findById(activeId);
    }
}
