package com.example.radarhumanapplication.eventlog;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.radarhumanapplication.R;

import java.util.ArrayList;
import java.util.List;

/** Simple RecyclerView adapter for {@link Event}s. */
public class EventAdapter extends RecyclerView.Adapter<EventAdapter.VH> {

    private final Context context;
    private final List<Event> entries = new ArrayList<>();

    public EventAdapter(Context context) {
        this.context = context;
    }

    public void setEntries(List<Event> list) {
        entries.clear();
        if (list != null) entries.addAll(list);
        notifyDataSetChanged();
    }

    public void addEntry(Event e) {
        entries.add(e);
        notifyItemInserted(entries.size() - 1);
    }

    public void clear() {
        int n = entries.size();
        entries.clear();
        notifyItemRangeRemoved(0, n);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_event, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Event e = entries.get(position);
        CharSequence time = DateFormat.format("HH:mm:ss", e.timestamp);
        h.tvTime.setText(time);
        h.tvType.setText(e.type != null ? e.type.name() : "?");
        h.tvDesc.setText(e.description != null ? e.description : "");
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvTime;
        final TextView tvType;
        final TextView tvDesc;
        VH(View v) {
            super(v);
            tvTime = v.findViewById(R.id.tv_event_time);
            tvType = v.findViewById(R.id.tv_event_type);
            tvDesc = v.findViewById(R.id.tv_event_desc);
        }
    }
}
