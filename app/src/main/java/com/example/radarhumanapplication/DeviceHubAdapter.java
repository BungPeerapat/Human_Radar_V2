package com.example.radarhumanapplication;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the Dashboard's Device Hub. Renders one row per
 * device that MQTT discovery has seen, with a status dot, IP/fw line, "just
 * now"-style freshness, and an ACTIVE badge when the row matches whatever
 * {@link MqttService#getDeviceName()} currently points at.
 *
 * <p>Tap a row to invoke {@link MqttService#switchActiveDevice(String)}.
 * The host fragment is expected to refresh the data set via
 * {@link #setEntries(List, String)} whenever discovery changes or the active
 * device changes — the adapter does no observation of its own.</p>
 */
public class DeviceHubAdapter
        extends RecyclerView.Adapter<DeviceHubAdapter.VH> {

    public interface OnDeviceTap {
        void onDeviceTap(MqttService.DiscoveredDevice d);
    }

    private final Context context;
    private final OnDeviceTap onTap;
    private final List<MqttService.DiscoveredDevice> entries = new ArrayList<>();
    private String activeDeviceName = "";

    public DeviceHubAdapter(Context context, OnDeviceTap onTap) {
        this.context = context;
        this.onTap = onTap;
    }

    public void setEntries(List<MqttService.DiscoveredDevice> list, String activeName) {
        entries.clear();
        if (list != null) entries.addAll(list);
        this.activeDeviceName = activeName == null ? "" : activeName;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_device_hub, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        MqttService.DiscoveredDevice d = entries.get(position);
        boolean online = d.isOnline();
        h.dot.setText(online ? "●" : "○");
        h.dot.setTextColor(online
                ? Color.parseColor("#00FF88")
                : Color.parseColor("#FF6666"));
        h.name.setText(d.deviceName);

        StringBuilder detail = new StringBuilder();
        if (d.ip != null && !d.ip.isEmpty()) detail.append(d.ip);
        if (d.fw != null && !d.fw.isEmpty()) {
            if (detail.length() > 0) detail.append("  ·  ");
            detail.append("v").append(d.fw);
        }
        if (detail.length() > 0) detail.append("  ·  ");
        detail.append(online ? "online" : ("offline " + formatAgo(d.lastSeenMs)));
        h.detail.setText(detail.toString());

        boolean isActive = !activeDeviceName.isEmpty()
                && activeDeviceName.equals(d.deviceName);
        h.badge.setVisibility(isActive ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(v -> {
            if (onTap != null) onTap.onDeviceTap(d);
        });
    }

    @Override
    public int getItemCount() { return entries.size(); }

    private static String formatAgo(long lastSeenMs) {
        if (lastSeenMs <= 0) return "(never)";
        long sec = (System.currentTimeMillis() - lastSeenMs) / 1000L;
        if (sec < 60)    return sec + "s ago";
        if (sec < 3600)  return (sec / 60) + "m ago";
        if (sec < 86400) return (sec / 3600) + "h ago";
        return String.format(Locale.US, "%dd ago", sec / 86400);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView dot, name, detail, badge;
        VH(View v) {
            super(v);
            dot    = v.findViewById(R.id.dh_dot);
            name   = v.findViewById(R.id.dh_name);
            detail = v.findViewById(R.id.dh_detail);
            badge  = v.findViewById(R.id.dh_active_badge);
        }
    }
}
