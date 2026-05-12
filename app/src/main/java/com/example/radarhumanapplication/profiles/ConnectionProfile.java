package com.example.radarhumanapplication.profiles;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * Saved MQTT broker / device connection profile. Plain POJO so Gson handles serialization
 * without custom adapters.
 */
public class ConnectionProfile {

    public String id;
    public String name;
    public String brokerHost;
    public int brokerPort;
    public String deviceName;
    public String username;
    public String password;
    /** ESP32 device's HTTP IP (e.g. "192.168.4.1") for /api/alert{,test} REST calls.
     *  Optional — empty string means "not configured". */
    public String espHttpIp = "";

    public ConnectionProfile() {
        // Required for Gson
    }

    public ConnectionProfile(String id, String name, String brokerHost, int brokerPort,
                             String deviceName, String username, String password) {
        this.id = id;
        this.name = name;
        this.brokerHost = brokerHost;
        this.brokerPort = brokerPort;
        this.deviceName = deviceName;
        this.username = username;
        this.password = password;
    }

    public static ConnectionProfile create(String name, String brokerHost, int brokerPort,
                                           String deviceName, String username, String password) {
        return new ConnectionProfile(UUID.randomUUID().toString(),
                name, brokerHost, brokerPort, deviceName, username, password);
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public JsonObject toJsonObject() {
        return new Gson().toJsonTree(this).getAsJsonObject();
    }

    public static ConnectionProfile fromJson(String json) {
        return new Gson().fromJson(json, ConnectionProfile.class);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConnectionProfile)) return false;
        ConnectionProfile p = (ConnectionProfile) o;
        return brokerPort == p.brokerPort
                && eq(id, p.id)
                && eq(name, p.name)
                && eq(brokerHost, p.brokerHost)
                && eq(deviceName, p.deviceName)
                && eq(username, p.username)
                && eq(password, p.password)
                && eq(espHttpIp, p.espHttpIp);
    }

    @Override
    public int hashCode() {
        int h = 17;
        h = 31 * h + (id == null ? 0 : id.hashCode());
        h = 31 * h + (name == null ? 0 : name.hashCode());
        h = 31 * h + (brokerHost == null ? 0 : brokerHost.hashCode());
        h = 31 * h + brokerPort;
        h = 31 * h + (deviceName == null ? 0 : deviceName.hashCode());
        h = 31 * h + (username == null ? 0 : username.hashCode());
        h = 31 * h + (password == null ? 0 : password.hashCode());
        h = 31 * h + (espHttpIp == null ? 0 : espHttpIp.hashCode());
        return h;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
