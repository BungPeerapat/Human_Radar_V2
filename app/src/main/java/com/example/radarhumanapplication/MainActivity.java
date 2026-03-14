package com.example.radarhumanapplication;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private final RadarFragment radarFragment = new RadarFragment();
    private final DashboardFragment dashboardFragment = new DashboardFragment();
    private final ConfigFragment configFragment = new ConfigFragment();
    private final LogsFragment logsFragment = new LogsFragment();
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

        // Add all fragments, hide non-default ones
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, logsFragment, "logs").hide(logsFragment)
                .add(R.id.fragment_container, configFragment, "config").hide(configFragment)
                .add(R.id.fragment_container, dashboardFragment, "dashboard").hide(dashboardFragment)
                .add(R.id.fragment_container, radarFragment, "radar")
                .commit();
        activeFragment = radarFragment;

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MqttService.getInstance().disconnect();
    }
}
