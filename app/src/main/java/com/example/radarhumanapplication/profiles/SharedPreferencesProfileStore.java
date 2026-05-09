package com.example.radarhumanapplication.profiles;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores connection profiles in {@code profile_prefs}. */
public class SharedPreferencesProfileStore implements ProfileStore {

    public static final String PREFS = "profile_prefs";
    public static final String KEY_PROFILES = "profiles_v1";
    public static final String KEY_ACTIVE = "active_id";

    private final SharedPreferences prefs;

    public SharedPreferencesProfileStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public String readProfilesJson() {
        return prefs.getString(KEY_PROFILES, null);
    }

    @Override
    public void writeProfilesJson(String json) {
        prefs.edit().putString(KEY_PROFILES, json).apply();
    }

    @Override
    public String readActiveId() {
        return prefs.getString(KEY_ACTIVE, null);
    }

    @Override
    public void writeActiveId(String id) {
        prefs.edit().putString(KEY_ACTIVE, id).apply();
    }
}
