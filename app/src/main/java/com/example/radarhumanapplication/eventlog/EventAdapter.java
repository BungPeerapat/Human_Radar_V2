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
import java.util.Locale;

/** Simple RecyclerView adapter for {@link Event}s with filter support. */
public class EventAdapter extends RecyclerView.Adapter<EventAdapter.VH> {

    private final Context context;
    /** Full event set, unfiltered. */
    private final List<Event> all = new ArrayList<>();
    /** Currently-visible (filtered) view of {@link #all}. */
    private final List<Event> entries = new ArrayList<>();

    private String searchQuery = "";
    /** null = all types, otherwise restrict to this type. */
    private Event.Type typeFilter = null;

    public EventAdapter(Context context) {
        this.context = context;
    }

    public void setEntries(List<Event> list) {
        all.clear();
        if (list != null) all.addAll(list);
        rebuildFiltered();
    }

    public void addEntry(Event e) {
        all.add(e);
        if (matches(e)) {
            entries.add(e);
            notifyItemInserted(entries.size() - 1);
        }
    }

    public void clear() {
        int n = entries.size();
        all.clear();
        entries.clear();
        notifyItemRangeRemoved(0, n);
    }

    /** Set free-text search filter (matched against description + type name). */
    public void setSearchQuery(String q) {
        this.searchQuery = q == null ? "" : q.trim().toLowerCase(Locale.US);
        rebuildFiltered();
    }

    /** Restrict to a single Event.Type. Pass {@code null} for "All". */
    public void setTypeFilter(Event.Type t) {
        this.typeFilter = t;
        rebuildFiltered();
    }

    private boolean matches(Event e) {
        if (e == null) return false;
        if (typeFilter != null && e.type != typeFilter) return false;
        if (searchQuery.isEmpty()) return true;
        String hay = (e.description == null ? "" : e.description.toLowerCase(Locale.US))
                   + " " + (e.type == null ? "" : e.type.name().toLowerCase(Locale.US));
        return hay.contains(searchQuery);
    }

    private void rebuildFiltered() {
        entries.clear();
        for (Event e : all) if (matches(e)) entries.add(e);
        notifyDataSetChanged();
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
