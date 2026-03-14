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

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

    private final List<MqttService.LogEntry> entries = new ArrayList<>();
    private final Context context;

    public LogAdapter(Context context) {
        this.context = context;
    }

    public void addEntry(MqttService.LogEntry entry) {
        entries.add(entry);
        notifyItemInserted(entries.size() - 1);
    }

    public void setEntries(List<MqttService.LogEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    public void clear() {
        entries.clear();
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
        holder.tvTag.setText(entry.tag);
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
