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
                service.configure(config.get("host").getAsString(), 8883,
                        config.get("device").getAsString(), config.get("username").getAsString(),
                        config.get("password").getAsString());
                service.connect();
            });
            assertTrue("Initial authenticated TLS connection failed", initial.await(30, TimeUnit.SECONDS));
            Bundle marker = new Bundle();
            marker.putString("stream", "READY_FOR_BROKER_RESTART\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, marker);
            assertTrue("Host did not restart the broker", disconnected.await(45, TimeUnit.SECONDS));
            assertTrue("Application failed to report automatic reconnection", restored.await(45, TimeUnit.SECONDS));
            assertTrue(service.isConnected());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                service.removeConnectionListener(listener);
                service.disconnect();
            });
        }
    }
}
