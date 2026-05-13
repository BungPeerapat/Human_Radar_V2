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
import java.util.Set;

/**
 * RecyclerView adapter for the Dashboard's Device Hub. Renders one row per
 * MQTT-discovered device with a status dot, IP/fw line, freshness, an ACTIVE
 * badge for the currently-subscribed device, and a 📌 indicator for pinned
 * devices. Pinned rows sort first.
 *
 * <p>Single tap → {@link OnDeviceTap#onDeviceTap(MqttService.DiscoveredDevice)}.
 * Long press → {@link OnDeviceLongPress#onDeviceLongPress(MqttService.DiscoveredDevice, View)}
 * — typically opens a context menu anchored to the row.</p>
 */
public class DeviceHubAdapter
        extends RecyclerView.Adapter<DeviceHubAdapter.VH> {

    public interface OnDeviceTap {
        void onDeviceTap(MqttService.DiscoveredDevice d);
    }
    public interface OnDeviceLongPress {
        void onDeviceLongPress(MqttService.DiscoveredDevice d, View anchor);
    }

    private final Context context;
    private final OnDeviceTap onTap;
    private final OnDeviceLongPress onLong;
    private final List<MqttService.DiscoveredDevice> entries = new ArrayList<>();
    private String activeDeviceName = "";
    private Set<String> pinned = java.util.Collections.emptySet();

    public DeviceHubAdapter(Context context, OnDeviceTap onTap) {
        this(context, onTap, null);
    }

    public DeviceHubAdapter(Context context, OnDeviceTap onTap,
                            OnDeviceLongPress onLong) {
        this.context = context;
        this.onTap = onTap;
        this.onLong = onLong;
    }

    public void setEntries(List<MqttService.DiscoveredDevice> list,
                           String activeName, Set<String> pinnedNames) {
        entries.clear();
        if (list != null) entries.addAll(list);
        this.activeDeviceName = activeName == null ? "" : activeName;
        this.pinned = pinnedNames == null ? java.util.Collections.emptySet() : pinnedNames;
        // Pinned devices first, then online, then by lastSeen desc.
        java.util.Collections.sort(entries, (a, b) -> {
            boolean ap = pinned.contains(a.deviceName);
            boolean bp = pinned.contains(b.deviceName);
            if (ap != bp) return ap ? -1 : 1;
            if (a.isOnline() != b.isOnline()) return a.isOnline() ? -1 : 1;
            return Long.compare(b.lastSeenMs, a.lastSeenMs);
        });
        notifyDataSetChanged();
    }

    /** Backwards-compat shim for callers that don't track pinning yet. */
    public void setEntries(List<MqttService.DiscoveredDevice> list, String activeName) {
        setEntries(list, activeName, java.util.Collections.emptySet());
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
        boolean isPinned = pinned.contains(d.deviceName);
        h.name.setText((isPinned ? "📌 " : "") + d.deviceName);

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
        h.itemView.setOnLongClickListener(v -> {
            if (onLong != null) {
                onLong.onDeviceLongPress(d, v);
                return true;
            }
            return false;
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
