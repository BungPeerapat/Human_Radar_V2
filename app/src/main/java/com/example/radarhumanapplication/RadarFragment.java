package com.example.radarhumanapplication;

import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.radarhumanapplication.alerts.AlertManager;
import com.example.radarhumanapplication.recording.SessionReplayer;
import com.google.gson.JsonObject;

import java.util.Locale;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class RadarFragment extends Fragment
        implements MqttService.TargetListener, MqttService.ConnectionListener,
                   MqttService.StatusListener,
                   AlertManager.RulesChangedListener, SessionReplayer.StateListener {

    /** No-frame timeout — if MQTT is still connected but no target frame
     *  arrives for this long, treat the device as silent and clear the canvas. */
    private static final long STALE_FRAME_MS = 3000;

    private static final String TAG = "RadarFragment";

    private RadarView radarView;
    private TextView tvTarget1, tvTarget2, tvTarget3;
    private TextView tvStats, tvRadarStatus;
    /** Last device status from MQTT humanradar/<name>/status; "" until first message. */
    private String deviceStatus = "";
    /** Millis of the last frame applied to the radar; 0 if never. */
    private long lastFrameMs = 0;
    private final Handler staleHandler = new Handler(Looper.getMainLooper());
    private final Runnable staleCheck = new Runnable() {
        @Override public void run() {
            if (!isAdded()) return;
            if (lastFrameMs > 0
                    && System.currentTimeMillis() - lastFrameMs > STALE_FRAME_MS) {
                radarView.clearTargets();
                updateConnectionStatus();   // may flip text to STALE
            }
            staleHandler.postDelayed(this, 1000);
        }
    };
    private View statusBar, infoPanel;
    private View uxOverlay;
    private View replayBadge;
    private TextView replayBadgeDetail;
    private ImageButton btnKeepScreenOn, btnManualRotate, btnLockOrientation, btnNightMode, btnSnapshot;
    private boolean isFullscreen = false;
    private MqttService mqtt;
    private RadarUiPrefs uiPrefs;

    private static final int[] TARGET_COLORS = {
            0xFFFF4444, 0xFF44FF44, 0xFF4488FF
    };
    private static final int[] TARGET_COLORS_NIGHT = {
            0xFFFF6666, 0xFFFF3333, 0xFFCC2222
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_radar, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        mqtt = MqttService.getInstance();
        uiPrefs = new RadarUiPrefs(requireContext());

        radarView = v.findViewById(R.id.radar_view);
        tvTarget1 = v.findViewById(R.id.tv_radar_t1);
        tvTarget2 = v.findViewById(R.id.tv_radar_t2);
        tvTarget3 = v.findViewById(R.id.tv_radar_t3);
        tvStats = v.findViewById(R.id.tv_radar_stats);
        tvRadarStatus = v.findViewById(R.id.tv_radar_status);
        statusBar = v.findViewById(R.id.radar_status_bar);
        infoPanel = v.findViewById(R.id.radar_info_panel);
        uxOverlay = v.findViewById(R.id.radar_ux_overlay);

        btnKeepScreenOn = v.findViewById(R.id.btn_keep_screen_on);
        btnManualRotate = v.findViewById(R.id.btn_manual_rotate);
        btnLockOrientation = v.findViewById(R.id.btn_lock_orientation);
        btnNightMode = v.findViewById(R.id.btn_night_mode);
        btnSnapshot = v.findViewById(R.id.btn_snapshot);
        replayBadge = v.findViewById(R.id.replay_badge);
        replayBadgeDetail = v.findViewById(R.id.replay_badge_detail);

        radarView.setInfoListener((targets, frameCount, errorCount, fps) -> {
            if (!isAdded()) return;
            updateTargetInfo(targets);
            tvStats.setText(String.format("Frames: %d | Errors: %d | FPS: %d",
                    frameCount, errorCount, fps));
        });

        // Tap radar to toggle fullscreen
        radarView.setOnClickListener(view -> toggleFullscreen());

        btnKeepScreenOn.setOnClickListener(view -> toggleKeepScreenOn());
        btnManualRotate.setOnClickListener(view -> manualRotate());
        btnLockOrientation.setOnClickListener(view -> toggleLockOrientation());
        btnNightMode.setOnClickListener(view -> toggleNightMode());
        btnSnapshot.setOnClickListener(view -> takeSnapshot());

        // Restore persisted UX prefs
        applyKeepScreenOn(uiPrefs.isKeepScreenOn());
        applyLockOrientation(uiPrefs.isLockOrientation());
        applyNightMode(uiPrefs.isNightMode());

        deviceStatus = mqtt.getDeviceStatus();
        updateConnectionStatus();
        applyAlertDistances();
        mqtt.addTargetListener(this);
        mqtt.addConnectionListener(this);
        mqtt.addStatusListener(this);
        AlertManager.getInstance().addRulesChangedListener(this);
        staleHandler.postDelayed(staleCheck, 1000);

        // Replay badge — sync initial state in case a replay is already running.
        SessionReplayer rep = SessionReplayer.getInstance();
        rep.addStateListener(this);
        onReplayStateChanged(rep.isReplaying(), rep.getCurrentFileName(), rep.getCurrentSpeed());
    }

    @Override
    public void onReplayStateChanged(boolean replaying, String fileName, double speed) {
        if (!isAdded() || replayBadge == null) return;
        if (replaying) {
            replayBadge.setVisibility(View.VISIBLE);
            String detail = (fileName == null || fileName.isEmpty()) ? "" : fileName;
            if (speed > 0 && Math.abs(speed - 1.0) > 0.001) {
                detail = detail + (detail.isEmpty() ? "" : " ")
                       + String.format(Locale.US, "@%sx", trimSpeed(speed));
            }
            replayBadgeDetail.setText(detail);
        } else {
            replayBadge.setVisibility(View.GONE);
        }
    }

    private static String trimSpeed(double s) {
        if (s == Math.floor(s)) return String.valueOf((int) s);
        return String.format(Locale.US, "%.2f", s).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @Override
    public void onResume() {
        super.onResume();
        // Re-apply window flags in case the activity recycled them
        if (uiPrefs != null) {
            applyKeepScreenOn(uiPrefs.isKeepScreenOn());
            applyLockOrientation(uiPrefs.isLockOrientation());
        }
    }

    @Override
    public void onPause() {
        // Always release the keep-screen-on flag when leaving the radar tab
        if (getActivity() != null) {
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onPause();
    }

    @Override
    public void onRulesChanged() {
        if (!isAdded()) return;
        applyAlertDistances();
    }

    private void applyAlertDistances() {
        if (radarView != null) {
            radarView.setAlertDistancesMm(AlertManager.getInstance().getEnabledDistancesMm());
        }
    }

    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;
        applyFullscreen();
    }

    private void applyFullscreen() {
        int hidden = isFullscreen ? View.GONE : View.VISIBLE;
        if (statusBar != null) statusBar.setVisibility(hidden);
        if (infoPanel != null) infoPanel.setVisibility(hidden);
        // tv_radar_stats lives outside infoPanel only in portrait — hide it directly too
        if (tvStats != null && tvStats.getParent() != infoPanel) {
            tvStats.setVisibility(hidden);
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(!isFullscreen);
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // Restore bottom nav visibility when user navigates away from this fragment
        if (hidden && isFullscreen && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        } else if (!hidden && isFullscreen && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(false);
        }
    }

    @Override
    public void onDestroyView() {
        // Make sure bottom nav is restored if fragment is destroyed while fullscreen
        if (isFullscreen && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setBottomNavVisible(true);
        }
        // Drop the keep-screen flag if still set
        if (getActivity() != null) {
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        mqtt.removeTargetListener(this);
        mqtt.removeConnectionListener(this);
        mqtt.removeStatusListener(this);
        AlertManager.getInstance().removeRulesChangedListener(this);
        SessionReplayer.getInstance().removeStateListener(this);
        staleHandler.removeCallbacks(staleCheck);
        super.onDestroyView();
    }

    @Override
    public void onTargetsReceived(JsonObject data) {
        if (!isAdded()) return;
        lastFrameMs = System.currentTimeMillis();
        radarView.updateTargets(data);
        updateConnectionStatus();
    }

    @Override
    public void onConnected() {
        if (!isAdded()) return;
        updateConnectionStatus();
    }

    @Override
    public void onDisconnected(String reason) {
        if (!isAdded()) return;
        // MQTT broker went away — every target on the canvas is stale.
        deviceStatus = "";
        radarView.clearTargets();
        updateConnectionStatus();
    }

    @Override
    public void onDeviceStatus(String status) {
        if (!isAdded()) return;
        this.deviceStatus = status == null ? "" : status.trim();
        if ("offline".equalsIgnoreCase(this.deviceStatus)) {
            // ESP32 published its LWT — drop any frozen targets from the canvas.
            radarView.clearTargets();
            lastFrameMs = 0;
        }
        updateConnectionStatus();
    }

    private void updateConnectionStatus() {
        if (!mqtt.isConnected()) {
            tvRadarStatus.setText("DISCONNECTED");
            tvRadarStatus.setTextColor(getColor(R.color.radar_red));
            return;
        }
        if ("offline".equalsIgnoreCase(deviceStatus)) {
            tvRadarStatus.setText("DEVICE OFFLINE");
            tvRadarStatus.setTextColor(getColor(R.color.radar_red));
            return;
        }
        if (lastFrameMs > 0
                && System.currentTimeMillis() - lastFrameMs > STALE_FRAME_MS) {
            tvRadarStatus.setText("NO DATA");
            tvRadarStatus.setTextColor(getColor(R.color.radar_yellow));
            return;
        }
        tvRadarStatus.setText("CONNECTED");
        tvRadarStatus.setTextColor(getColor(R.color.radar_green));
    }

    private void updateTargetInfo(RadarView.TargetData[] targets) {
        TextView[] tvs = {tvTarget1, tvTarget2, tvTarget3};
        int[] palette = (uiPrefs != null && uiPrefs.isNightMode())
                ? TARGET_COLORS_NIGHT : TARGET_COLORS;
        for (int i = 0; i < 3; i++) {
            RadarView.TargetData t = targets[i];
            if (t.present) {
                String dir = t.speed < 0 ? "↑" : "↓";
                tvs[i].setText(String.format("T%d  X:%+5d  Y:%5d  D:%5dmm  %s%dcm/s",
                        i + 1, t.x, t.y, t.distance, dir, Math.abs(t.speed)));
                tvs[i].setTextColor(palette[i]);
            } else {
                tvs[i].setText(String.format("T%d  ---", i + 1));
                tvs[i].setTextColor(getColor(R.color.radar_text_dim));
            }
        }
    }

    private int getColor(int resId) {
        return requireContext().getColor(resId);
    }

    // ----- UX overlay handlers -----

    private void toggleKeepScreenOn() {
        boolean next = !uiPrefs.isKeepScreenOn();
        uiPrefs.setKeepScreenOn(next);
        applyKeepScreenOn(next);
        Toast.makeText(requireContext(),
                next ? "Keep screen on: ON" : "Keep screen on: OFF",
                Toast.LENGTH_SHORT).show();
    }

    private void applyKeepScreenOn(boolean enabled) {
        if (getActivity() == null) return;
        if (enabled) {
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        tintToggleButton(btnKeepScreenOn, enabled);
    }

    private void toggleLockOrientation() {
        boolean next = !uiPrefs.isLockOrientation();
        uiPrefs.setLockOrientation(next);
        applyLockOrientation(next);
        Toast.makeText(requireContext(),
                next ? "Orientation locked" : "Orientation unlocked",
                Toast.LENGTH_SHORT).show();
    }

    private void applyLockOrientation(boolean locked) {
        if (getActivity() == null) return;
        int target = locked
                ? ActivityInfo.SCREEN_ORIENTATION_LOCKED
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        requireActivity().setRequestedOrientation(target);
        tintToggleButton(btnLockOrientation, locked);
    }

    private void manualRotate() {
        if (getActivity() == null) return;
        int slot = uiPrefs.getManualRotationSlot();
        int next = RadarUiPrefs.nextRotationSlot(slot);
        uiPrefs.setManualRotationSlot(next);
        // USER_* variants honor the user's request even when system auto-rotate is OFF.
        int target = (next == 1)
                ? ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        requireActivity().setRequestedOrientation(target);
    }

    private void toggleNightMode() {
        boolean next = !uiPrefs.isNightMode();
        uiPrefs.setNightMode(next);
        applyNightMode(next);
    }

    private void applyNightMode(boolean enabled) {
        if (radarView != null) radarView.setNightMode(enabled);
        // Tint surrounding chrome so target panel + status bar match the red theme.
        int statusBg = enabled ? Color.parseColor("#1A0000") : getColor(R.color.radar_card);
        int infoBg = enabled ? Color.parseColor("#100000") : getColor(R.color.radar_card);
        int statsBg = enabled ? Color.parseColor("#0A0000") : getColor(R.color.radar_bg);
        int dimText = enabled ? Color.parseColor("#883333") : getColor(R.color.radar_text_dim);
        if (statusBar != null) statusBar.setBackgroundColor(statusBg);
        if (infoPanel != null) infoPanel.setBackgroundColor(infoBg);
        if (tvStats != null) {
            tvStats.setBackgroundColor(statsBg);
            tvStats.setTextColor(dimText);
        }
        tintToggleButton(btnNightMode, enabled);
    }

    private void tintToggleButton(ImageButton btn, boolean enabled) {
        if (btn == null) return;
        if (enabled) {
            btn.setColorFilter(new PorterDuffColorFilter(
                    Color.parseColor("#FFCC00"), PorterDuff.Mode.SRC_IN));
        } else {
            btn.clearColorFilter();
        }
    }

    private void takeSnapshot() {
        if (radarView == null || radarView.getWidth() == 0) {
            Toast.makeText(requireContext(), "Radar not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bmp = radarView.captureSnapshot();
        String filename = "radar_" + System.currentTimeMillis() + ".png";
        String savedPath = saveBitmapToPictures(bmp, filename);
        bmp.recycle();
        if (savedPath != null) {
            Toast.makeText(requireContext(), "Saved: " + savedPath, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(requireContext(), "Snapshot failed", Toast.LENGTH_SHORT).show();
        }
    }

    private String saveBitmapToPictures(Bitmap bmp, String filename) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(bmp, filename);
        } else {
            return saveLegacyFile(bmp, filename);
        }
    }

    private String saveViaMediaStore(Bitmap bmp, String filename) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/HumanRadar");
            Uri uri = requireContext().getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri)) {
                if (out == null) return null;
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return uri.toString();
        } catch (Exception e) {
            Log.e(TAG, "MediaStore save failed", e);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private String saveLegacyFile(Bitmap bmp, String filename) {
        try {
            File picturesDir = Environment
                    .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File outDir = new File(picturesDir, "HumanRadar");
            if (!outDir.exists() && !outDir.mkdirs()) {
                Log.e(TAG, "Could not create dir " + outDir);
                return null;
            }
            File outFile = new File(outDir, filename);
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Legacy save failed", e);
            return null;
        }
    }
}
