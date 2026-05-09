package com.example.radarhumanapplication;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.radarhumanapplication.alerts.AlertRule;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

public class AlertRuleAdapter extends RecyclerView.Adapter<AlertRuleAdapter.VH> {

    public interface Callbacks {
        void onToggle(AlertRule rule, boolean enabled);
        void onEdit(AlertRule rule);
        void onDelete(AlertRule rule);
    }

    private final List<AlertRule> rules = new ArrayList<>();
    private final Callbacks callbacks;

    public AlertRuleAdapter(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void submit(List<AlertRule> next) {
        rules.clear();
        if (next != null) rules.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_alert_rule, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AlertRule r = rules.get(position);
        h.title.setText(r.displayName());
        String sub = r.streamLabel() + " • " + (r.soundLabel == null ? "(none)" : r.soundLabel)
                + (r.loop ? " • loop" : "");
        h.subtitle.setText(sub);

        StringBuilder badges = new StringBuilder();
        if (r.vibrate) {
            badges.append("🔊 vibrate");
            if (r.vibrationPattern != null && !r.vibrationPattern.isEmpty()) {
                badges.append(" (").append(r.vibrationPattern).append(")");
            }
        }
        if (r.speak) {
            if (badges.length() > 0) badges.append(" • ");
            badges.append("🗣 speak");
        }
        if (r.cooldownSeconds > 0) {
            if (badges.length() > 0) badges.append(" • ");
            badges.append("cooldown ").append(r.cooldownSeconds).append("s");
        }
        if (badges.length() == 0) {
            h.badges.setVisibility(View.GONE);
        } else {
            h.badges.setVisibility(View.VISIBLE);
            h.badges.setText(badges.toString());
        }

        h.toggle.setOnCheckedChangeListener(null);
        h.toggle.setChecked(r.enabled);
        h.toggle.setOnCheckedChangeListener((btn, on) -> callbacks.onToggle(r, on));
        h.editBtn.setOnClickListener(v -> callbacks.onEdit(r));
        h.deleteBtn.setOnClickListener(v -> callbacks.onDelete(r));
    }

    @Override
    public int getItemCount() {
        return rules.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final MaterialSwitch toggle;
        final TextView title, subtitle, badges;
        final ImageButton editBtn, deleteBtn;

        VH(@NonNull View itemView) {
            super(itemView);
            toggle = itemView.findViewById(R.id.sw_alert_enabled);
            title = itemView.findViewById(R.id.tv_rule_title);
            subtitle = itemView.findViewById(R.id.tv_rule_subtitle);
            badges = itemView.findViewById(R.id.tv_rule_badges);
            editBtn = itemView.findViewById(R.id.btn_rule_edit);
            deleteBtn = itemView.findViewById(R.id.btn_rule_delete);
        }
    }
}
