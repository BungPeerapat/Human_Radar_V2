package com.example.radarhumanapplication;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.radarhumanapplication.update.UpdateDialog;
import com.example.radarhumanapplication.update.UpdateManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private static final String TAG_RADAR = "radar";
    private static final String TAG_DASHBOARD = "dashboard";
    private static final String TAG_CONFIG = "config";
    private static final String TAG_LOGS = "logs";
    private static final String STATE_ACTIVE_TAG = "active_tag";

    private RadarFragment radarFragment;
    private DashboardFragment dashboardFragment;
    private ConfigFragment configFragment;
    private LogsFragment logsFragment;
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // On rotation FragmentManager restores fragments by tag — reuse them; otherwise create + add.
        if (savedInstanceState == null) {
            radarFragment = new RadarFragment();
            dashboardFragment = new DashboardFragment();
            configFragment = new ConfigFragment();
            logsFragment = new LogsFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, logsFragment, TAG_LOGS).hide(logsFragment)
                    .add(R.id.fragment_container, configFragment, TAG_CONFIG).hide(configFragment)
                    .add(R.id.fragment_container, dashboardFragment, TAG_DASHBOARD).hide(dashboardFragment)
                    .add(R.id.fragment_container, radarFragment, TAG_RADAR)
                    .commit();
            activeFragment = radarFragment;
        } else {
            radarFragment = (RadarFragment) getSupportFragmentManager().findFragmentByTag(TAG_RADAR);
            dashboardFragment = (DashboardFragment) getSupportFragmentManager().findFragmentByTag(TAG_DASHBOARD);
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
        if (TAG_CONFIG.equals(tag)) return configFragment;
        if (TAG_LOGS.equals(tag)) return logsFragment;
        return radarFragment;
    }

    private String tagForFragment(Fragment f) {
        if (f == dashboardFragment) return TAG_DASHBOARD;
        if (f == configFragment) return TAG_CONFIG;
        if (f == logsFragment) return TAG_LOGS;
        return TAG_RADAR;
    }

    private int menuIdForFragment(Fragment f) {
        if (f == dashboardFragment) return R.id.nav_dashboard;
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
        MqttService.getInstance().disconnect();
    }
}
