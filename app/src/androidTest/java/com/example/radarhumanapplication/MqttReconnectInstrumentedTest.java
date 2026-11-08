package com.example.radarhumanapplication;

import android.content.Context;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in live broker test. The host restarts the broker after the READY marker. */
@RunWith(AndroidJUnit4.class)
public class MqttReconnectInstrumentedTest {
    @Test public void brokerRestartRestoresApplicationConnectionState() throws Exception {
        String fixtureName = InstrumentationRegistry.getArguments().getString("brokerFixture");
        int brokerPort = Integer.parseInt(InstrumentationRegistry.getArguments().getString("brokerPort", "8883"));
        assumeTrue("Live broker fixture not supplied", fixtureName != null);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JsonObject config;
        try (FileReader reader = new FileReader(new File(context.getFilesDir(), fixtureName))) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        }
        MqttService service = MqttService.getInstance();
        CountDownLatch initial = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        CountDownLatch restored = new CountDownLatch(1);
        CountDownLatch health = new CountDownLatch(1);
        String requestId = java.util.UUID.randomUUID().toString();
        MqttService.AnyCmdAckListener healthListener = (device, ack) -> {
            if (config.get("device").getAsString().equals(device)
                    && ack.has("request_id") && requestId.equals(ack.get("request_id").getAsString())
                    && ack.has("status") && "ok".equals(ack.get("status").getAsString())) health.countDown();
        };
        MqttService.ConnectionListener listener = new MqttService.ConnectionListener() {
            @Override public void onConnected() {
                if (initial.getCount() > 0) initial.countDown();
                else restored.countDown();
            }
            @Override public void onDisconnected(String reason) {
                if (initial.getCount() == 0) disconnected.countDown();
            }
        };
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                service.attachContext(context);
                service.addConnectionListener(listener);
                service.configure(config.get("host").getAsString(), brokerPort,
                        config.get("device").getAsString(), config.get("username").getAsString(),
                        config.get("password").getAsString());
                service.connect();
            });
            assertTrue("Initial authenticated TLS connection failed", initial.await(30, TimeUnit.SECONDS));
            Bundle marker = new Bundle();
            marker.putString("stream", "READY_FOR_BROKER_RESTART\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, marker);
            assertTrue("Host did not restart the broker", disconnected.await(45, TimeUnit.SECONDS));
            boolean reconnected = restored.await(45, TimeUnit.SECONDS);
            java.lang.reflect.Field clientField = MqttService.class.getDeclaredField("client");
            clientField.setAccessible(true);
            com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient runtimeClient =
                    (com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient) clientField.get(service);
            String lastReason = "";
            for (MqttService.LogEntry entry : service.getLogBuffer()) {
                if ("(app)".equals(entry.device) && "MQTT".equals(entry.tag)
                        && entry.message.startsWith("Disconnected:")) lastReason = entry.message;
            }
            assertTrue("Application failed to report automatic reconnection; SDK state="
                    + (runtimeClient == null ? "null" : runtimeClient.getState()) + "; " + lastReason, reconnected);
            assertTrue(service.isConnected());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                service.addAnyCmdAckListener(healthListener);
            });
            // The ESP32 has its own reconnect backoff and can lag behind Android.
            // Health is idempotent; retry until the board has re-subscribed.
            long deadline = android.os.SystemClock.elapsedRealtime() + 30_000;
            while (health.getCount() > 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        service.sendTrackedCommand(config.get("device").getAsString(), "health", requestId,
                                new JsonObject(), error -> {}));
                health.await(2, TimeUnit.SECONDS);
            }
            assertEquals("Command/ACK subscriptions did not recover", 0, health.getCount());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                service.removeConnectionListener(listener);
                service.removeAnyCmdAckListener(healthListener);
                service.disconnect();
            });
        }
    }
}
