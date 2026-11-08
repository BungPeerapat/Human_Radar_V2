package com.example.radarhumanapplication;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.radarhumanapplication.update.FirmwareManifest;
import com.example.radarhumanapplication.update.FirmwareUpdater;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in: flashes the explicitly provisioned test board using the private fixture. */
@RunWith(AndroidJUnit4.class)
public class FirmwareMqttOtaInstrumentedTest {
    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @Test public void rejectedImageReportsErrorThenValidImageInstallsAndVerifies() throws Exception {
        String fixtureName = InstrumentationRegistry.getArguments().getString("brokerFixture");
        assumeTrue("Live OTA fixture not supplied", fixtureName != null);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JsonObject config;
        try (FileReader reader = new FileReader(new File(context.getFilesDir(), fixtureName))) {
            config = JsonParser.parseReader(reader).getAsJsonObject();
        }
        assumeTrue("No explicitly selected test firmware", config.has("ota_url"));
        String device = config.get("device").getAsString();
        MqttService service = MqttService.getInstance();
        FirmwareUpdater updater = new FirmwareUpdater(context);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch health = new CountDownLatch(1);
        String healthId = UUID.randomUUID().toString();
        MqttService.ConnectionListener connectionListener = new MqttService.ConnectionListener() {
            @Override public void onConnected() { connected.countDown(); }
            @Override public void onDisconnected(String reason) {}
        };
        MqttService.AnyCmdAckListener healthListener = (sender, ack) -> {
            if (device.equals(sender) && ack.has("request_id")
                    && healthId.equals(ack.get("request_id").getAsString())) health.countDown();
        };
        try {
            main(() -> {
                service.attachContext(context);
                service.addConnectionListener(connectionListener);
                service.addAnyCmdAckListener(healthListener);
                service.configure(config.get("host").getAsString(), 8883, device,
                        config.get("username").getAsString(), config.get("password").getAsString());
                service.connect();
            });
            assertTrue("MQTT connection failed", connected.await(30, TimeUnit.SECONDS));
            for (int attempt = 0; attempt < 5 && health.getCount() > 0; attempt++) {
                main(() -> service.sendTrackedCommand(device, "health", healthId, new JsonObject(), error -> {}));
                health.await(2, TimeUnit.SECONDS);
            }
            assertEquals("Test board is not responding", 0, health.getCount());
            FirmwareManifest manifest = new FirmwareManifest();
            manifest.versionName = config.get("ota_version").getAsString();
            manifest.binUrl = config.get("ota_url").getAsString();
            manifest.sizeBytes = config.get("ota_size").getAsLong();
            manifest.sha256 = new String(new char[64]).replace('\0', '0');
            CountDownLatch rejected = new CountDownLatch(1);
            AtomicReference<String> rejection = new AtomicReference<>();
            AtomicBoolean wronglyUploaded = new AtomicBoolean();
            main(() -> updater.installViaMqtt(manifest, device, new FirmwareUpdater.InstallCallback() {
                @Override public void onProgress(int percent, String label) {}
                @Override public void onUploaded() { wronglyUploaded.set(true); rejected.countDown(); }
                @Override public void onInstalled(FirmwareUpdater.MqttVerifyResult info) { wronglyUploaded.set(true); rejected.countDown(); }
                @Override public void onError(String message) { rejection.set(message); rejected.countDown(); }
            }));
            assertTrue("Device rejection ACK did not reach the updater", rejected.await(90, TimeUnit.SECONDS));
            assertFalse("A rejected image was reported as uploaded", wronglyUploaded.get());
            assertTrue("Checksum rejection reason was lost", rejection.get() != null && rejection.get().contains("SHA-256"));

            manifest.sha256 = config.get("ota_sha256").getAsString();
            CountDownLatch installed = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();
            AtomicReference<FirmwareUpdater.MqttVerifyResult> result = new AtomicReference<>();
            main(() -> updater.installViaMqtt(manifest, device, new FirmwareUpdater.InstallCallback() {
                @Override public void onProgress(int percent, String label) {}
                @Override public void onUploaded() { updater.verifyOnMqtt(device, manifest.versionName, 90_000, this); }
                @Override public void onInstalled(FirmwareUpdater.MqttVerifyResult info) { result.set(info); installed.countDown(); }
                @Override public void onError(String message) { error.set(message); installed.countDown(); }
            }));
            assertTrue("OTA did not finish", installed.await(180, TimeUnit.SECONDS));
            assertNull("Updater returned an error", error.get());
            assertNotNull(result.get());
            assertEquals(manifest.versionName, result.get().fw);
            assertTrue(result.get().versionMatches);
        } finally {
            main(() -> {
                updater.cancel();
                service.removeConnectionListener(connectionListener);
                service.removeAnyCmdAckListener(healthListener);
                service.disconnect();
            });
        }
    }
}
