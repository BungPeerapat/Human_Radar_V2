package com.example.radarhumanapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.radarhumanapplication.alerts.AlertManager;
import com.example.radarhumanapplication.update.UpdateDialog;
import com.example.radarhumanapplication.update.UpdateManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_RADAR = "radar";
    private static final String TAG_DASHBOARD = "dashboard";
    private static final String TAG_ALERTS = "alerts";
    private static final String TAG_CONFIG = "config";
    private static final String TAG_LOGS = "logs";
    private static final String STATE_ACTIVE_TAG = "active_tag";

    private RadarFragment radarFragment;
    private DashboardFragment dashboardFragment;
    private AlertsFragment alertsFragment;
    private ConfigFragment configFragment;
    private LogsFragment logsFragment;
    private Fragment activeFragment;

    private final ActivityResultLauncher<String> notifPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Bind app context to MqttService so it can spin up the foreground service.
        MqttService.getInstance().attachContext(getApplicationContext());

        // AlertManager subscribes itself to MqttService.TargetListener — attach early so rules
        // fire even when the user is not on the Alerts tab.
        AlertManager.getInstance().attach(getApplicationContext());

        // Start the LAN/Cloud/Hybrid transport orchestrator. Decides per-device
        // whether radar frames come from cloud MQTT or a direct LAN WebSocket,
        // and feeds both into MqttService.dispatchTargetsToListeners so the rest
        // of the app doesn't have to care.
        com.example.radarhumanapplication.transport.HybridTransportManager
                .get(getApplicationContext()).start();

        // Android 13+ requires runtime POST_NOTIFICATIONS permission for the foreground service notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // On rotation FragmentManager restores fragments by tag — reuse them; otherwise create + add.
        if (savedInstanceState == null) {
            radarFragment = new RadarFragment();
            dashboardFragment = new DashboardFragment();
            alertsFragment = new AlertsFragment();
            configFragment = new ConfigFragment();
            logsFragment = new LogsFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, logsFragment, TAG_LOGS).hide(logsFragment)
                    .add(R.id.fragment_container, configFragment, TAG_CONFIG).hide(configFragment)
                    .add(R.id.fragment_container, alertsFragment, TAG_ALERTS).hide(alertsFragment)
                    .add(R.id.fragment_container, dashboardFragment, TAG_DASHBOARD).hide(dashboardFragment)
                    .add(R.id.fragment_container, radarFragment, TAG_RADAR)
                    .commit();
            activeFragment = radarFragment;
        } else {
            radarFragment = (RadarFragment) getSupportFragmentManager().findFragmentByTag(TAG_RADAR);
            dashboardFragment = (DashboardFragment) getSupportFragmentManager().findFragmentByTag(TAG_DASHBOARD);
            alertsFragment = (AlertsFragment) getSupportFragmentManager().findFragmentByTag(TAG_ALERTS);
            configFragment = (ConfigFragment) getSupportFragmentManager().findFragmentByTag(TAG_CONFIG);
            logsFragment = (LogsFragment) getSupportFragmentManager().findFragmentByTag(TAG_LOGS);
            String activeTag = savedInstanceState.getString(STATE_ACTIVE_TAG, TAG_RADAR);
            activeFragment = fragmentForTag(activeTag);
        }

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment target;
            int id = item.getItemId();
            if (id == R.id.nav_radar) {
                target = radarFragment;
            } else if (id == R.id.nav_dashboard) {
                target = dashboardFragment;
            } else if (id == R.id.nav_alerts) {
                target = alertsFragment;
            } else if (id == R.id.nav_config) {
                target = configFragment;
            } else if (id == R.id.nav_logs) {
                target = logsFragment;
            } else {
                return false;
            }

            if (target != activeFragment) {
                getSupportFragmentManager().beginTransaction()
                        .hide(activeFragment)
                        .show(target)
                        .commit();
                activeFragment = target;
            }
            return true;
        });

        if (savedInstanceState != null && activeFragment != null) {
            // Sync bottom nav highlight with restored active fragment
            bottomNav.setSelectedItemId(menuIdForFragment(activeFragment));
        }

        // Background update check (debounced 6h inside UpdateManager)
        UpdateManager.getInstance().checkAuto(this, result -> {
            if (isFinishing() || isDestroyed()) return;
            if (result.status == UpdateManager.Status.UPDATE_AVAILABLE && result.info != null) {
                UpdateDialog.show(getSupportFragmentManager(), result.info);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ACTIVE_TAG, tagForFragment(activeFragment));
    }

    private Fragment fragmentForTag(String tag) {
        if (TAG_DASHBOARD.equals(tag)) return dashboardFragment;
        if (TAG_ALERTS.equals(tag)) return alertsFragment;
        if (TAG_CONFIG.equals(tag)) return configFragment;
        if (TAG_LOGS.equals(tag)) return logsFragment;
        return radarFragment;
    }

    private String tagForFragment(Fragment f) {
        if (f == dashboardFragment) return TAG_DASHBOARD;
        if (f == alertsFragment) return TAG_ALERTS;
        if (f == configFragment) return TAG_CONFIG;
        if (f == logsFragment) return TAG_LOGS;
        return TAG_RADAR;
    }

    private int menuIdForFragment(Fragment f) {
        if (f == dashboardFragment) return R.id.nav_dashboard;
        if (f == alertsFragment) return R.id.nav_alerts;
        if (f == configFragment) return R.id.nav_config;
        if (f == logsFragment) return R.id.nav_logs;
        return R.id.nav_radar;
    }

    public void setBottomNavVisible(boolean visible) {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null) {
            nav.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Intentionally do NOT disconnect MQTT here — the foreground service keeps the
        // connection alive after the activity is destroyed (background, screen off, app switch).
        // User can disconnect explicitly via the Dashboard, or by stopping the notification.
    }
}
