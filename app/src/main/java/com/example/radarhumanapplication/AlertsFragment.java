package com.example.radarhumanapplication;

import android.app.Activity;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.radarhumanapplication.alerts.AlertManager;
import com.example.radarhumanapplication.alerts.AlertOperator;
import com.example.radarhumanapplication.alerts.AlertRule;
import com.example.radarhumanapplication.alerts.AlertSoundPlayer;
import com.example.radarhumanapplication.alerts.AlertTtsPlayer;
import com.example.radarhumanapplication.alerts.AlertVibrator;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

public class AlertsFragment extends Fragment implements AlertManager.RulesChangedListener {

    /** How long the editor's "▶ Test" preview plays before being stopped, in ms. */
    private static final int TEST_PREVIEW_MS = 2000;

    private AlertRuleAdapter adapter;
    private TextView tvCount;
    private TextView tvEmpty;
    private RecyclerView recycler;

    /** Holds the rule we're editing while waiting for the ringtone picker result. */
    private AlertRule pendingRule;
    private TextView pendingSoundLabel;

    /** Lazily allocated test-preview helpers (created when first ▶ Test pressed). */
    private AlertSoundPlayer testSoundPlayer;
    private AlertTtsPlayer testTts;
    private AlertVibrator testVibrator;
    private final Handler testHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Intent> ringtonePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) return;
                // Type-safe getParcelableExtra introduced in API 33. Fall back to the legacy
                // overload (and suppress its deprecation warning) on older devices.
                Uri uri;
                Intent data = result.getData();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class);
                } else {
                    @SuppressWarnings("deprecation")
                    Uri legacy = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                    uri = legacy;
                }
                if (pendingRule != null) {
                    if (uri != null) {
                        pendingRule.soundUri = uri.toString();
                        // RingtoneManager.getRingtone() can return null if the picked
                        // URI points to a clip that has been deleted or lives on
                        // unmounted storage — guard before .getTitle() to avoid NPE.
                        android.media.Ringtone rt =
                                RingtoneManager.getRingtone(requireContext(), uri);
                        if (rt != null) {
                            pendingRule.soundLabel = rt.getTitle(requireContext());
                        } else {
                            String tail = uri.getLastPathSegment();
                            pendingRule.soundLabel = (tail != null && !tail.isEmpty())
                                    ? tail : "(unknown)";
                        }
                    } else {
                        pendingRule.soundUri = null;
                        pendingRule.soundLabel = "(silent)";
                    }
                    if (pendingSoundLabel != null) {
                        pendingSoundLabel.setText(pendingRule.soundLabel);
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alerts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        AlertManager.getInstance().attach(requireContext().getApplicationContext());

        tvCount = v.findViewById(R.id.tv_alert_count);
        tvEmpty = v.findViewById(R.id.tv_alert_empty);
        recycler = v.findViewById(R.id.rv_alerts);
        recycler.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new AlertRuleAdapter(new AlertRuleAdapter.Callbacks() {
            @Override
            public void onToggle(AlertRule rule, boolean enabled) {
                AlertManager.getInstance().setEnabled(rule.id, enabled);
            }
            @Override
            public void onEdit(AlertRule rule) {
                showEditor(rule, /*isNew=*/false);
            }
            @Override
            public void onDelete(AlertRule rule) {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Delete rule?")
                        .setMessage("Remove \"" + rule.displayName() + "\"?")
                        .setPositiveButton("Delete", (d, w) -> AlertManager.getInstance().removeRule(rule.id))
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        recycler.setAdapter(adapter);

        FloatingActionButton fab = v.findViewById(R.id.fab_add_alert);
        fab.setOnClickListener(view -> showEditor(new AlertRule(), /*isNew=*/true));

        AlertManager.getInstance().addRulesChangedListener(this);
        refreshList();
    }

    @Override
    public void onDestroyView() {
        AlertManager.getInstance().removeRulesChangedListener(this);
        stopTestPreview();
        if (testTts != null) {
            try { testTts.releaseAll(); } catch (Exception ignored) {}
            testTts = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onRulesChanged() {
        if (!isAdded()) return;
        refreshList();
    }

    private void refreshList() {
        var rules = AlertManager.getInstance().getRules();
        adapter.submit(rules);
        tvCount.setText(rules.size() + (rules.size() == 1 ? " rule" : " rules"));
        tvEmpty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(rules.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // -------- Editor dialog --------

    private void showEditor(AlertRule original, boolean isNew) {
        // Work on a copy so cancel doesn't mutate
        AlertRule draft = cloneRule(original);
        pendingRule = draft;

        View root = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_alert_rule_editor, null);
        TextInputEditText etName = root.findViewById(R.id.et_rule_name);
        TextInputEditText etDistance = root.findViewById(R.id.et_distance);
        Spinner spOperator = root.findViewById(R.id.sp_operator);
        Spinner spUnit = root.findViewById(R.id.sp_unit);
        Spinner spTarget = root.findViewById(R.id.sp_target);
        Spinner spStream = root.findViewById(R.id.sp_stream);
        TextView tvSound = root.findViewById(R.id.tv_selected_sound);
        MaterialSwitch swLoop = root.findViewById(R.id.sw_loop);
        MaterialSwitch swVibrate = root.findViewById(R.id.sw_vibrate);
        Spinner spVibrationPattern = root.findViewById(R.id.sp_vibration_pattern);
        MaterialSwitch swSpeak = root.findViewById(R.id.sw_speak);
        TextInputEditText etSpoken = root.findViewById(R.id.et_spoken_text);
        TextInputEditText etCooldown = root.findViewById(R.id.et_cooldown);
        pendingSoundLabel = tvSound;

        // Operator spinner
        String[] opSymbols = {AlertOperator.LT.symbol, AlertOperator.LE.symbol,
                AlertOperator.EQ.symbol, AlertOperator.GE.symbol, AlertOperator.GT.symbol};
        spOperator.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, opSymbols));
        spOperator.setSelection(indexOf(opSymbols, draft.operator.symbol));

        // Unit spinner (m / mm)
        String[] units = {"m", "mm"};
        spUnit.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, units));
        spUnit.setSelection(0);

        // Target spinner
        String[] targets = {"Any target", "T1", "T2", "T3"};
        spTarget.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, targets));
        spTarget.setSelection(draft.targetIndex == AlertRule.TARGET_ANY ? 0 : (draft.targetIndex + 1));

        // Stream spinner
        spStream.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, AlertRule.streamOptionLabels()));
        int[] streamOpts = AlertRule.streamOptions();
        int streamIdx = 0;
        for (int i = 0; i < streamOpts.length; i++) {
            if (streamOpts[i] == draft.audioStream) { streamIdx = i; break; }
        }
        spStream.setSelection(streamIdx);

        // Vibration pattern spinner
        String[] vibPatterns = AlertRule.vibrationPatternOptions();
        spVibrationPattern.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, vibPatterns));
        spVibrationPattern.setSelection(indexOf(vibPatterns,
                draft.vibrationPattern == null ? AlertRule.VIB_SHORT : draft.vibrationPattern));

        // Initial values
        etName.setText(draft.name == null ? "" : draft.name);
        etDistance.setText(formatDistance(draft.distanceMm));
        tvSound.setText(draft.soundLabel == null ? "(none)" : draft.soundLabel);
        swLoop.setChecked(draft.loop);
        swVibrate.setChecked(draft.vibrate);
        swSpeak.setChecked(draft.speak);
        etSpoken.setText(draft.spokenText == null ? AlertRule.DEFAULT_SPOKEN_TEXT : draft.spokenText);
        etCooldown.setText(String.valueOf(Math.max(0, draft.cooldownSeconds)));

        root.findViewById(R.id.btn_pick_sound).setOnClickListener(v -> openRingtonePicker(draft));
        root.findViewById(R.id.btn_test_sound).setOnClickListener(v -> {
            // Build an ephemeral rule reflecting current dialog state for the preview.
            AlertRule preview = cloneRule(draft);
            preview.id = "test-preview";
            preview.audioStream = streamOpts[spStream.getSelectedItemPosition()];
            preview.loop = false; // never loop a preview
            preview.vibrate = swVibrate.isChecked();
            Object selectedPattern = spVibrationPattern.getSelectedItem();
            preview.vibrationPattern = selectedPattern == null
                    ? AlertRule.VIB_SHORT : selectedPattern.toString();
            preview.speak = swSpeak.isChecked();
            preview.spokenText = etSpoken.getText() == null
                    ? AlertRule.DEFAULT_SPOKEN_TEXT : etSpoken.getText().toString();
            preview.operator = AlertOperator.fromSymbol((String) spOperator.getSelectedItem());
            preview.distanceMm = parseDistanceMm(
                    etDistance.getText() == null ? "" : etDistance.getText().toString(),
                    spUnit.getSelectedItemPosition() == 0);
            playTestPreview(preview);
        });

        new AlertDialog.Builder(requireContext())
                .setTitle(isNew ? "New alert rule" : "Edit alert rule")
                .setView(root)
                .setPositiveButton("Save", (d, w) -> {
                    draft.name = etName.getText() == null ? "" : etName.getText().toString().trim();
                    draft.operator = AlertOperator.fromSymbol((String) spOperator.getSelectedItem());
                    int targetSel = spTarget.getSelectedItemPosition();
                    draft.targetIndex = targetSel == 0 ? AlertRule.TARGET_ANY : (targetSel - 1);
                    draft.audioStream = streamOpts[spStream.getSelectedItemPosition()];
                    draft.loop = swLoop.isChecked();
                    draft.distanceMm = parseDistanceMm(
                            etDistance.getText() == null ? "" : etDistance.getText().toString(),
                            spUnit.getSelectedItemPosition() == 0);
                    draft.vibrate = swVibrate.isChecked();
                    Object pat = spVibrationPattern.getSelectedItem();
                    draft.vibrationPattern = pat == null ? AlertRule.VIB_SHORT : pat.toString();
                    draft.speak = swSpeak.isChecked();
                    draft.spokenText = etSpoken.getText() == null
                            ? AlertRule.DEFAULT_SPOKEN_TEXT
                            : etSpoken.getText().toString().trim();
                    if (draft.spokenText.isEmpty()) draft.spokenText = AlertRule.DEFAULT_SPOKEN_TEXT;
                    draft.cooldownSeconds = parseCooldownSeconds(
                            etCooldown.getText() == null ? "" : etCooldown.getText().toString());
                    if (isNew) {
                        AlertManager.getInstance().addRule(draft);
                    } else {
                        AlertManager.getInstance().updateRule(draft);
                    }
                    pendingRule = null;
                    pendingSoundLabel = null;
                    stopTestPreview();
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    pendingRule = null;
                    pendingSoundLabel = null;
                    stopTestPreview();
                })
                .setOnDismissListener(d -> stopTestPreview())
                .show();
    }

    private void openRingtonePicker(AlertRule rule) {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_ALARM | RingtoneManager.TYPE_NOTIFICATION | RingtoneManager.TYPE_RINGTONE);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Pick alert sound");
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        if (rule.soundUri != null) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(rule.soundUri));
        }
        ringtonePicker.launch(intent);
    }

    /** Play sound + TTS + vibration for ~2 seconds, stopping early if the dialog closes. */
    private void playTestPreview(AlertRule preview) {
        stopTestPreview();
        if (testSoundPlayer == null) testSoundPlayer = new AlertSoundPlayer(requireContext());
        if (testTts == null) testTts = new AlertTtsPlayer(requireContext());
        if (testVibrator == null) testVibrator = new AlertVibrator(requireContext());

        try { testSoundPlayer.play(preview); } catch (Exception ignored) {}
        try { testVibrator.vibrate(preview); } catch (Exception ignored) {}
        try { testTts.speak(preview, /*targetIndex=*/0, preview.distanceMm); } catch (Exception ignored) {}

        testHandler.postDelayed(this::stopTestPreview, TEST_PREVIEW_MS);
    }

    private void stopTestPreview() {
        testHandler.removeCallbacksAndMessages(null);
        if (testSoundPlayer != null) {
            try { testSoundPlayer.stopAll(); } catch (Exception ignored) {}
        }
        if (testVibrator != null) {
            try { testVibrator.cancel(); } catch (Exception ignored) {}
        }
    }

    // -------- Helpers --------

    private static String formatDistance(int mm) {
        if (mm % 1000 == 0) return String.valueOf(mm / 1000);
        return String.valueOf(mm / 1000.0);
    }

    private static int parseDistanceMm(String text, boolean isMeters) {
        try {
            double v = Double.parseDouble(text.trim());
            return (int) Math.round(isMeters ? v * 1000.0 : v);
        } catch (NumberFormatException e) {
            return 1000;
        }
    }

    private static int parseCooldownSeconds(String text) {
        try {
            int v = Integer.parseInt(text.trim());
            return Math.max(0, v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int indexOf(String[] arr, String needle) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(needle)) return i;
        return 0;
    }

    private static AlertRule cloneRule(AlertRule src) {
        AlertRule r = new AlertRule();
        r.id = src.id;
        r.enabled = src.enabled;
        r.name = src.name;
        r.operator = src.operator;
        r.distanceMm = src.distanceMm;
        r.targetIndex = src.targetIndex;
        r.soundUri = src.soundUri;
        r.soundLabel = src.soundLabel;
        r.audioStream = src.audioStream;
        r.loop = src.loop;
        r.vibrate = src.vibrate;
        r.vibrationPattern = src.vibrationPattern;
        r.speak = src.speak;
        r.spokenText = src.spokenText;
        r.ttsLanguageTag = src.ttsLanguageTag;
        r.cooldownSeconds = src.cooldownSeconds;
        return r;
    }
}
