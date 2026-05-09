package com.example.radarhumanapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.gson.JsonObject;

/**
 * Long-running service that keeps the MQTT connection alive when the app is in the background,
 * the screen is off, or the user switches apps.
 *
 * Lifecycle:
 *   - {@link MqttService#connect()} starts this service via {@link #start(Context)}.
 *   - The service shows a persistent foreground notification ("MQTT connected to ...") so Android
 *     does not reclaim the process.
 *   - A partial wake lock keeps the CPU running for MQTT keepalive packets while the screen is off.
 *   - The service registers as a connection listener and updates the notification text as state
 *     changes (connected / disconnected / device online).
 *   - {@link MqttService#disconnect()} stops the service via {@link #stop(Context)}.
 *
 * Without this service, Android's Doze mode and background restrictions kill the MqttService
 * singleton's network sockets within seconds of leaving the app.
 */
public class MqttForegroundService extends Service
        implements MqttService.ConnectionListener, MqttService.StatusListener {

    private static final String TAG = "MqttForegroundService";
    private static final String CHANNEL_ID = "mqtt_connection";
    private static final int NOTIFICATION_ID = 7421;
    private static final String WAKE_LOCK_TAG = "HumanRadar:MqttForeground";

    private MqttService mqtt;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), MqttForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.getApplicationContext().startForegroundService(i);
        } else {
            ctx.getApplicationContext().startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), MqttForegroundService.class);
        ctx.getApplicationContext().stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service created");
        createChannel();
        mqtt = MqttService.getInstance();
        mqtt.addConnectionListener(this);
        mqtt.addStatusListener(this);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification(currentStateText());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires a foreground service type.
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.i(TAG, "Wake lock acquired");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroyed");
        if (mqtt != null) {
            mqtt.removeConnectionListener(this);
            mqtt.removeStatusListener(this);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "Wake lock released");
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- MqttService.ConnectionListener ---

    @Override
    public void onConnected() {
        updateNotification();
    }

    @Override
    public void onDisconnected(String reason) {
        updateNotification();
    }

    // --- MqttService.StatusListener ---

    @Override
    public void onDeviceStatus(String status) {
        updateNotification();
    }

    // --- Notification helpers ---

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MQTT connection",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the radar feed alive while the app is in the background");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String stateText) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Human Radar")
                .setContentText(stateText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(contentIntent)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(NOTIFICATION_ID, buildNotification(currentStateText()));
    }

    private String currentStateText() {
        if (mqtt == null) return "Idle";
        if (!mqtt.isConnected()) {
            return "MQTT disconnected — reconnecting…";
        }
        String host = mqtt.getBrokerHost();
        String dev = mqtt.getDeviceName();
        String devStatus = mqtt.getDeviceStatus();
        StringBuilder sb = new StringBuilder("Connected to ");
        sb.append(host == null || host.isEmpty() ? "broker" : host);
        sb.append(" • ").append(dev);
        if (devStatus != null && !"unknown".equals(devStatus)) {
            sb.append(" [").append(devStatus).append("]");
        }
        return sb.toString();
    }
}
