package com.example.radarhumanapplication.transport;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.example.radarhumanapplication.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Map;
import java.util.Set;

/**
 * Single-screen settings UI for the transport layer:
 *   - master enable
 *   - mode picker (Cloud / LAN / Hybrid) with inline descriptions
 *   - advanced section (mDNS, badge, verbose, timeouts, manual IPs)
 *   - live status of every device's transport lane
 *
 * <p>All changes apply immediately via {@link TransportSettings} — the
 * {@link HybridTransportManager} listens for them and re-evaluates without
 * requiring an explicit Save. The dialog shows a Close button only.
 */
public class TransportSettingsDialog extends DialogFragment
        implements HybridTransportManager.StatusListener {

    public static final String TAG = "TransportSettings";

    public static void show(FragmentManager fm) {
        new TransportSettingsDialog().show(fm, TAG);
    }

    private TransportSettings settings;
    private HybridTransportManager manager;

    private MaterialSwitch swEnabled, swPreferLan, swMdns, swBadge, swVerbose;
    private RadioGroup rgMode;
    private RadioButton rbCloud, rbLan, rbHybrid;
    private TextView tvCloudDesc, tvLanDesc, tvHybridDesc;
    private MaterialButton btnToggleAdvanced;
    private LinearLayout groupAdvanced, groupManualIps, groupLiveStatus;
    private TextInputEditText etProbe, etReconnect, etFallback, etManualIp;
    private MaterialButton btnAddManualIp;

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context ctx = requireContext();
        settings = TransportSettings.get(ctx);
        manager  = HybridTransportManager.get(ctx);

        View view = LayoutInflater.from(ctx)
                .inflate(R.layout.dialog_transport_settings, null);
        bindViews(view);
        loadFromSettings();
        wireListeners();
        renderManualIpList();
        renderLiveStatus(manager.snapshot());

        return new AlertDialog.Builder(ctx)
                .setTitle("Transport settings")
                .setView(view)
                .setPositiveButton("Close", null)
                .create();
    }

    @Override
    public void onResume() {
        super.onResume();
        manager.addStatusListener(this);
        renderLiveStatus(manager.snapshot());
    }

    @Override
    public void onPause() {
        manager.removeStatusListener(this);
        super.onPause();
    }

    // ---------- view binding ----------
    private void bindViews(View v) {
        swEnabled         = v.findViewById(R.id.sw_transport_enabled);
        swPreferLan       = v.findViewById(R.id.sw_prefer_lan);
        swMdns            = v.findViewById(R.id.sw_mdns);
        swBadge           = v.findViewById(R.id.sw_show_badge);
        swVerbose         = v.findViewById(R.id.sw_verbose);
        rgMode            = v.findViewById(R.id.rg_transport_mode);
        rbCloud           = v.findViewById(R.id.rb_mode_cloud);
        rbLan             = v.findViewById(R.id.rb_mode_lan);
        rbHybrid          = v.findViewById(R.id.rb_mode_hybrid);
        tvCloudDesc       = v.findViewById(R.id.tv_mode_cloud_desc);
        tvLanDesc         = v.findViewById(R.id.tv_mode_lan_desc);
        tvHybridDesc      = v.findViewById(R.id.tv_mode_hybrid_desc);
        btnToggleAdvanced = v.findViewById(R.id.btn_toggle_advanced);
        groupAdvanced     = v.findViewById(R.id.group_advanced);
        groupManualIps    = v.findViewById(R.id.group_manual_ip_list);
        groupLiveStatus   = v.findViewById(R.id.group_live_status);
        etProbe           = v.findViewById(R.id.et_probe_ms);
        etReconnect       = v.findViewById(R.id.et_ws_reconnect_ms);
        etFallback        = v.findViewById(R.id.et_fallback_ms);
        etManualIp        = v.findViewById(R.id.et_manual_ip);
        btnAddManualIp    = v.findViewById(R.id.btn_add_manual_ip);
    }

    private void loadFromSettings() {
        swEnabled.setChecked(settings.isEnabled());
        swPreferLan.setChecked(settings.isPreferLanOnSameWifi());
        swMdns.setChecked(settings.isMdnsEnabled());
        swBadge.setChecked(settings.isShowBadge());
        swVerbose.setChecked(settings.isVerboseLog());
        switch (settings.getMode()) {
            case CLOUD:  rbCloud.setChecked(true);  break;
            case LAN:    rbLan.setChecked(true);    break;
            case HYBRID: default: rbHybrid.setChecked(true); break;
        }
        tvCloudDesc.setText(TransportMode.CLOUD.description());
        tvLanDesc.setText(TransportMode.LAN.description());
        tvHybridDesc.setText(TransportMode.HYBRID.description());
        etProbe.setText(String.valueOf(settings.getTcpProbeTimeoutMs()));
        etReconnect.setText(String.valueOf(settings.getWsReconnectMs()));
        etFallback.setText(String.valueOf(settings.getFallbackGraceMs()));
    }

    private void wireListeners() {
        swEnabled.setOnCheckedChangeListener((b, on)   -> settings.setEnabled(on));
        swPreferLan.setOnCheckedChangeListener((b, on) -> settings.setPreferLanOnSameWifi(on));
        swMdns.setOnCheckedChangeListener((b, on)      -> settings.setMdnsEnabled(on));
        swBadge.setOnCheckedChangeListener((b, on)     -> settings.setShowBadge(on));
        swVerbose.setOnCheckedChangeListener((b, on)   -> settings.setVerboseLog(on));

        rgMode.setOnCheckedChangeListener((g, checkedId) -> {
            if (checkedId == R.id.rb_mode_cloud)       settings.setMode(TransportMode.CLOUD);
            else if (checkedId == R.id.rb_mode_lan)    settings.setMode(TransportMode.LAN);
            else if (checkedId == R.id.rb_mode_hybrid) settings.setMode(TransportMode.HYBRID);
        });

        btnToggleAdvanced.setOnClickListener(v -> {
            boolean show = groupAdvanced.getVisibility() != View.VISIBLE;
            groupAdvanced.setVisibility(show ? View.VISIBLE : View.GONE);
            btnToggleAdvanced.setText(show
                    ? "▲  Advanced settings"
                    : "▼  Advanced settings");
        });

        etProbe.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) commitInt(etProbe, settings::setTcpProbeTimeoutMs,
                    TransportSettings.DEFAULT_TCP_PROBE_TIMEOUT_MS);
        });
        etReconnect.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) commitInt(etReconnect, settings::setWsReconnectMs,
                    TransportSettings.DEFAULT_WS_RECONNECT_MS);
        });
        etFallback.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) commitInt(etFallback, settings::setFallbackGraceMs,
                    TransportSettings.DEFAULT_FALLBACK_GRACE_MS);
        });

        btnAddManualIp.setOnClickListener(v -> {
            String ip = etManualIp.getText() != null
                    ? etManualIp.getText().toString().trim() : "";
            if (ip.isEmpty()) {
                Toast.makeText(requireContext(), "Enter an IP first", Toast.LENGTH_SHORT).show();
                return;
            }
            settings.addManualDirectIp(ip);
            etManualIp.setText("");
            renderManualIpList();
        });
    }

    private interface IntSink { void accept(int v); }

    private void commitInt(TextInputEditText et, IntSink sink, int fallback) {
        try {
            String s = et.getText() != null ? et.getText().toString().trim() : "";
            int v = s.isEmpty() ? fallback : Integer.parseInt(s);
            sink.accept(v);
        } catch (NumberFormatException e) {
            et.setText(String.valueOf(fallback));
            sink.accept(fallback);
        }
    }

    // ---------- manual IPs ----------
    private void renderManualIpList() {
        groupManualIps.removeAllViews();
        Set<String> ips = settings.getManualDirectIps();
        if (ips.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("(none)");
            empty.setTextSize(12);
            empty.setTextColor(getResources().getColor(R.color.radar_text_dim,
                    requireContext().getTheme()));
            empty.setPadding(0, 4, 0, 4);
            groupManualIps.addView(empty);
            return;
        }
        for (String ip : ips) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, 4, 0, 4);

            TextView tv = new TextView(requireContext());
            tv.setText("• " + ip);
            tv.setTextSize(13);
            tv.setTextColor(getResources().getColor(R.color.white,
                    requireContext().getTheme()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(tv, lp);

            MaterialButton del = new MaterialButton(requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            del.setText("✕");
            del.setTextSize(12);
            del.setMinHeight(28);
            del.setInsetTop(0);
            del.setInsetBottom(0);
            del.setPadding(20, 0, 20, 0);
            del.setOnClickListener(v -> {
                settings.removeManualDirectIp(ip);
                renderManualIpList();
            });
            row.addView(del);

            groupManualIps.addView(row);
        }
    }

    // ---------- live status ----------
    @Override
    public void onTransportStatusChanged(Map<String,
            HybridTransportManager.DeviceTransportStatus> snapshot) {
        renderLiveStatus(snapshot);
    }

    private void renderLiveStatus(Map<String,
            HybridTransportManager.DeviceTransportStatus> snapshot) {
        if (groupLiveStatus == null) return;
        groupLiveStatus.removeAllViews();
        if (snapshot.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("(no devices yet)");
            empty.setTextSize(12);
            empty.setTextColor(getResources().getColor(R.color.radar_text_dim,
                    requireContext().getTheme()));
            empty.setPadding(0, 4, 0, 4);
            groupLiveStatus.addView(empty);
            return;
        }
        for (HybridTransportManager.DeviceTransportStatus s : snapshot.values()) {
            TextView tv = new TextView(requireContext());
            String laneIcon;
            switch (s.lane) {
                case LAN_WS:     laneIcon = "📡 LAN";  break;
                case CLOUD_MQTT: laneIcon = "☁ Cloud"; break;
                default:         laneIcon = "—";        break;
            }
            String wsState = s.wsState == null ? "—" : s.wsState.name();
            tv.setText(String.format("• %s  →  %s   (ws: %s%s)",
                    s.deviceName, laneIcon, wsState,
                    s.ip.isEmpty() ? "" : (", " + s.ip)));
            tv.setTextSize(12);
            tv.setTextColor(getResources().getColor(R.color.radar_text_dim,
                    requireContext().getTheme()));
            tv.setPadding(0, 4, 0, 4);
            groupLiveStatus.addView(tv);
        }
    }
}
