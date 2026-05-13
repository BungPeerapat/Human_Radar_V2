package com.example.radarhumanapplication;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for ESP32 log entries with optional device-name filter.
 * Mirrors the EventAdapter filter shape so the Logs tab has consistent UX.
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

    /** Full backing set (every log we've received). */
    private final List<MqttService.LogEntry> all = new ArrayList<>();
    /** Filtered view the RecyclerView actually renders. */
    private final List<MqttService.LogEntry> entries = new ArrayList<>();
    /** "" = show every device. */
    private String deviceFilter = "";
    private final Context context;

    public LogAdapter(Context context) {
        this.context = context;
    }

    public void addEntry(MqttService.LogEntry entry) {
        all.add(entry);
        if (matches(entry)) {
            entries.add(entry);
            notifyItemInserted(entries.size() - 1);
        }
    }

    public void setEntries(List<MqttService.LogEntry> newEntries) {
        all.clear();
        if (newEntries != null) all.addAll(newEntries);
        rebuildFiltered();
    }

    public void clear() {
        int n = entries.size();
        all.clear();
        entries.clear();
        notifyItemRangeRemoved(0, n);
    }

    /** {@code ""} = no filter (show everything). */
    public void setDeviceFilter(String deviceName) {
        this.deviceFilter = deviceName == null ? "" : deviceName;
        rebuildFiltered();
    }

    private boolean matches(MqttService.LogEntry e) {
        if (e == null) return false;
        if (deviceFilter.isEmpty()) return true;
        return deviceFilter.equals(e.device);
    }

    private void rebuildFiltered() {
        entries.clear();
        for (MqttService.LogEntry e : all) if (matches(e)) entries.add(e);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MqttService.LogEntry entry = entries.get(position);
        holder.tvLevel.setText(entry.level);
        // When no filter is active, prefix the tag with the device name so the
        // user can tell rows apart at a glance. When a filter IS active, all
        // rows are from the same device — no need to repeat it.
        String tag = entry.tag == null ? "" : entry.tag;
        if (deviceFilter.isEmpty() && entry.device != null && !entry.device.isEmpty()) {
            tag = entry.device + "·" + tag;
        }
        holder.tvTag.setText(tag);
        holder.tvMsg.setText(entry.message);

        int levelColor;
        switch (entry.level) {
            case "ERROR": levelColor = context.getColor(R.color.radar_red); break;
            case "WARN":  levelColor = context.getColor(R.color.radar_yellow); break;
            case "DEBUG": levelColor = context.getColor(R.color.radar_text_dim); break;
            default:      levelColor = context.getColor(R.color.radar_green); break;
        }
        holder.tvLevel.setTextColor(levelColor);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvLevel, tvTag, tvMsg;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLevel = itemView.findViewById(R.id.tv_log_level);
            tvTag = itemView.findViewById(R.id.tv_log_tag);
            tvMsg = itemView.findViewById(R.id.tv_log_msg);
        }
    }
}
