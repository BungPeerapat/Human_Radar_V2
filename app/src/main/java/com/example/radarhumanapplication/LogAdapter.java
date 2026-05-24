package com.example.radarhumanapplication;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

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

    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MqttService.LogEntry entry = entries.get(position);

        // Timestamp: prefer wall clock when ts > a year ago. ESP-side ts is
        // millis-since-boot (small number) — show that raw so devs can
        // correlate with serial output; app-side ts is epoch millis.
        String time;
        if (entry.timestamp > 1_000_000_000_000L) {
            time = TIME_FMT.format(new Date(entry.timestamp));
        } else {
            time = String.format(Locale.US, "+%d.%03ds",
                    entry.timestamp / 1000, entry.timestamp % 1000);
        }
        holder.tvTime.setText(time);

        // Coloured level chip with white-on-color background.
        int chipBg, stripeColor;
        String level = entry.level == null ? "?" : entry.level;
        switch (level) {
            case "ERROR":
                chipBg = stripeColor = context.getColor(R.color.radar_red);  break;
            case "WARN":
                chipBg = stripeColor = context.getColor(R.color.radar_yellow); break;
            case "DEBUG":
                chipBg = stripeColor = context.getColor(R.color.radar_text_dim); break;
            case "RADAR":
                chipBg = stripeColor = context.getColor(R.color.radar_blue); break;
            default:
                chipBg = stripeColor = context.getColor(R.color.radar_green); break;
        }
        holder.tvLevel.setText(pad5(level));
        holder.tvLevel.setBackgroundColor(chipBg);
        holder.vStripe.setBackgroundColor(stripeColor);

        holder.tvTag.setText(entry.tag == null ? "" : entry.tag);

        // Show device only when the "all devices" view is active. When the
        // user has filtered to one device the column is redundant noise.
        if (deviceFilter.isEmpty()
                && entry.device != null && !entry.device.isEmpty()) {
            holder.tvDevice.setText(entry.device);
            holder.tvDevice.setVisibility(View.VISIBLE);
        } else {
            holder.tvDevice.setVisibility(View.GONE);
        }

        holder.tvMsg.setText(entry.message);

        // Heap / uptime meta line — only when the ESP actually reported them.
        StringBuilder meta = new StringBuilder();
        if (entry.freeHeap > 0) meta.append("heap=").append(entry.freeHeap / 1024).append(" kB");
        if (entry.uptime > 0) {
            if (meta.length() > 0) meta.append("   ");
            meta.append("up=").append(entry.uptime).append("s");
        }
        if (meta.length() > 0) {
            holder.tvMeta.setText(meta);
            holder.tvMeta.setVisibility(View.VISIBLE);
        } else {
            holder.tvMeta.setVisibility(View.GONE);
        }
    }

    /** Right-pad to 5 chars so the level chip width stays stable. */
    private static String pad5(String s) {
        if (s.length() >= 5) return s.substring(0, 5);
        StringBuilder sb = new StringBuilder(5);
        sb.append(s);
        while (sb.length() < 5) sb.append(' ');
        return sb.toString();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View vStripe;
        final TextView tvTime, tvLevel, tvTag, tvDevice, tvMsg, tvMeta;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            vStripe  = itemView.findViewById(R.id.v_log_stripe);
            tvTime   = itemView.findViewById(R.id.tv_log_time);
            tvLevel  = itemView.findViewById(R.id.tv_log_level);
            tvTag    = itemView.findViewById(R.id.tv_log_tag);
            tvDevice = itemView.findViewById(R.id.tv_log_device);
            tvMsg    = itemView.findViewById(R.id.tv_log_msg);
            tvMeta   = itemView.findViewById(R.id.tv_log_meta);
        }
    }
}
