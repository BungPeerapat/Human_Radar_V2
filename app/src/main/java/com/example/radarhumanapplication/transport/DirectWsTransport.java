package com.example.radarhumanapplication.transport;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * One direct WebSocket connection to an ESP32's port 81 radar stream.
 *
 * <p>The ESP32 firmware broadcasts radar frames as JSON text via
 * {@code _ws.broadcastTXT(json, len)} in
 * {@code WebRadarServer::broadcastFrame} — identical payload shape to the
 * MQTT {@code humanradar/<name>/targets} topic. We just tag the parsed
 * frame with {@code _dev} so downstream consumers can route per-device.
 *
 * <p>Auto-reconnects with the configured backoff. Closing is idempotent.
 */
public final class DirectWsTransport {

    public interface Listener {
        /** Frame received over WS. JSON is identical to the MQTT shape. */
        void onFrame(String deviceName, JsonObject data);
        /** Transport state changed (connected, disconnected, failure). */
        void onState(String deviceName, State state, String reason);
    }

    public enum State { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

    private static final String TAG = "DirectWsTransport";

    private final String deviceName;
    private final String ip;
    private final int    port;
    private final int    reconnectMs;
    private final Listener listener;
    private final OkHttpClient http;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    private volatile WebSocket socket;
    private volatile boolean   closed;
    private volatile State     state = State.DISCONNECTED;
    private volatile long      lastFrameMs = 0;

    public DirectWsTransport(String deviceName, String ip, int port,
                             int reconnectMs, Listener listener) {
        this.deviceName  = deviceName;
        this.ip          = ip;
        this.port        = port;
        this.reconnectMs = Math.max(500, reconnectMs);
        this.listener    = listener;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5,  TimeUnit.SECONDS)
                .readTimeout(0,     TimeUnit.MILLISECONDS) // 0 = no-timeout for stream
                .pingInterval(15,   TimeUnit.SECONDS)
                .build();
    }

    public String getDeviceName() { return deviceName; }
    public String getIp()         { return ip; }
    public State  getState()      { return state; }
    public long   getLastFrameMs(){ return lastFrameMs; }

    public synchronized void connect() {
        if (closed) return;
        if (socket != null) return;
        publishState(State.CONNECTING, null);
        Request req = new Request.Builder()
                .url("ws://" + ip + ":" + port + "/")
                .build();
        socket = http.newWebSocket(req, new Inner());
    }

    public synchronized void close() {
        closed = true;
        if (socket != null) {
            try { socket.close(1000, "client close"); } catch (Exception ignored) {}
            socket = null;
        }
        publishState(State.DISCONNECTED, "closed");
    }

    private void scheduleReconnect() {
        if (closed) return;
        main.postDelayed(() -> {
            synchronized (DirectWsTransport.this) {
                if (closed) return;
                socket = null;
                connect();
            }
        }, reconnectMs);
    }

    private void publishState(State next, String reason) {
        this.state = next;
        if (listener == null) return;
        main.post(() -> listener.onState(deviceName, next, reason));
    }

    private final class Inner extends WebSocketListener {
        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.i(TAG, "WS open " + deviceName + " @ " + ip);
            publishState(State.CONNECTED, null);
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            lastFrameMs = System.currentTimeMillis();
            try {
                JsonObject obj = gson.fromJson(text, JsonObject.class);
                if (obj == null) return;
                obj.addProperty("_dev", deviceName);
                obj.addProperty("_transport", "lan");
                if (listener != null) {
                    main.post(() -> listener.onFrame(deviceName, obj));
                }
            } catch (Exception e) {
                Log.w(TAG, "Bad WS frame from " + deviceName, e);
            }
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            // Firmware uses text broadcast; binary path reserved for future
            // binary frame format. Ignore for now.
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            try { ws.close(code, reason); } catch (Exception ignored) {}
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.i(TAG, "WS closed " + deviceName + " code=" + code + " reason=" + reason);
            publishState(State.DISCONNECTED, reason);
            scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.w(TAG, "WS failure " + deviceName + ": " + t.getMessage());
            publishState(State.FAILED, t.getMessage());
            scheduleReconnect();
        }
    }
}
