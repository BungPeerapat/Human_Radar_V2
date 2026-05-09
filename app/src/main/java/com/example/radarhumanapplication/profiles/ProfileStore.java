package com.example.radarhumanapplication.profiles;

/**
 * Persistence backend for {@link ProfileManager}. Production uses
 * {@link SharedPreferencesProfileStore}; tests use an in-memory implementation.
 */
public interface ProfileStore {
    String readProfilesJson();
    void writeProfilesJson(String json);
    String readActiveId();
    void writeActiveId(String id);
}
