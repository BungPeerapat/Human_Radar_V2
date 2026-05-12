package com.example.radarhumanapplication.update;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.example.radarhumanapplication.BuildConfig;
import com.example.radarhumanapplication.R;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.gson.Gson;

import java.io.File;
import java.util.Locale;

/**
 * Shows release notes + Update / Later / Skip buttons. Manages the download and install handoff
 * end-to-end. Hosting Activity does not need to plumb anything beyond calling
 * {@link #show(FragmentManager, UpdateInfo)}.
 *
 * <p><b>v2 changes (May 2026):</b></p>
 * <ul>
 *   <li>Replaced deprecated {@code ProgressDialog} (which crashes on some Android 14 builds) with
 *       an {@link AlertDialog} hosting a {@link LinearProgressIndicator}.</li>
 *   <li>All UI callbacks guard with {@link #isAdded()} so a detached fragment can no longer crash
 *       on {@code requireContext()}.</li>
 *   <li>Every UI mutation is wrapped in try/catch + {@code Log.e} so the actual stack trace
 *       reaches logcat instead of just closing the app.</li>
 * </ul>
 */
public class UpdateDialog extends DialogFragment {

    private static final String TAG = "UpdateDialog";
    private static final String ARG_INFO = "info";

    private UpdateInfo info;
    private ApkDownloader downloader;
    private AlertDialog progressDialog;
    private LinearProgressIndicator progressBar;
    private TextView progressMessage;
    private TextView progressDetail;
    private File pendingApk;

    public static void show(FragmentManager fm, UpdateInfo info) {
        UpdateDialog d = new UpdateDialog();
        Bundle b = new Bundle();
        b.putString(ARG_INFO, new Gson().toJson(info));
        d.setArguments(b);
        d.show(fm, TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        info = new Gson().fromJson(requireArguments().getString(ARG_INFO), UpdateInfo.class);

        String size = info.sizeBytes > 0
                ? Formatter.formatShortFileSize(requireContext(), info.sizeBytes)
                : "size unknown";
        String body = "v" + info.versionName
                + " (current v" + BuildConfig.VERSION_NAME + ")\n"
                + size + "\n\n"
                + (info.releaseNotes != null ? info.releaseNotes : "(no release notes)");

        AlertDialog.Builder b = new AlertDialog.Builder(requireContext())
                .setTitle(info.mandatory ? "Required update" : "Update available")
                .setMessage(body)
                // null listener so the OnShowListener below installs our own click
                // handler — without that, AlertDialog auto-dismisses the dialog on
                // click, which kills the host DialogFragment and any child
                // progress dialog we just created (bug seen in <= v1.0.20).
                .setPositiveButton("Update", null);

        if (!info.mandatory) {
            b.setNegativeButton("Later", (d, w) -> dismissSafe());
            b.setNeutralButton("Skip this version", (d, w) -> {
                if (isAdded()) {
                    UpdateManager.getInstance().skipVersion(requireContext(), info.versionCode);
                }
                dismissSafe();
            });
            setCancelable(true);
        } else {
            setCancelable(false);
        }
        AlertDialog dialog = b.create();
        dialog.setCanceledOnTouchOutside(!info.mandatory);
        dialog.setOnShowListener(d -> {
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) {
                positive.setOnClickListener(v -> startDownload());
            }
        });
        return dialog;
    }

    private void startDownload() {
        if (!isAdded()) return;
        try {
            if (!ApkInstaller.canInstall(requireContext())) {
                showPermissionExplainer();
                return;
            }
            // Hide (don't dismiss) the release-notes dialog while the progress
            // dialog runs — keeps the Fragment alive so callbacks can update UI.
            Dialog host = getDialog();
            if (host != null && host.isShowing()) host.hide();
            beginDownload();
        } catch (Exception e) {
            Log.e(TAG, "startDownload failed", e);
            toastSafe("Could not start update: " + e.getMessage());
        }
    }

    private void showPermissionExplainer() {
        if (!isAdded()) return;
        try {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Permission required")
                    .setMessage("To install updates, this app needs the \"Install unknown apps\" permission.\n\n"
                            + "Steps:\n"
                            + "1. Tap OPEN SETTINGS below\n"
                            + "2. Toggle ON \"Allow from this source\"\n"
                            + "3. Press BACK to return here\n"
                            + "4. Tap UPDATE again")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        try {
                            ApkInstaller.requestInstallPermission(requireContext());
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open install permission settings", e);
                            toastSafe("Open Settings → Apps → Human Radar → Install unknown apps → Allow");
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showPermissionExplainer failed", e);
        }
    }

    private void beginDownload() {
        if (!isAdded()) return;

        // Build a custom AlertDialog hosting a LinearProgressIndicator. We avoid the deprecated
        // ProgressDialog, which has been linked to BadTokenException crashes on Android 14 when
        // setMessage()/setProgress() runs after the host window detaches.
        try {
            View view = LayoutInflater.from(requireContext())
                    .inflate(R.layout.dialog_update_progress, null);
            progressMessage = view.findViewById(R.id.update_progress_message);
            progressBar     = view.findViewById(R.id.update_progress_bar);
            progressDetail  = view.findViewById(R.id.update_progress_detail);

            progressMessage.setText("Starting download…");
            progressBar.setIndeterminate(true);
            progressDetail.setText("Connecting to update server");

            progressDialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Downloading update")
                    .setView(view)
                    .setCancelable(true)
                    .setNegativeButton("Cancel", (d, w) -> {
                        if (downloader != null) downloader.cancel();
                    })
                    .create();
            progressDialog.setOnCancelListener(d -> {
                if (downloader != null) downloader.cancel();
            });
            progressDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build progress dialog", e);
            toastSafe("Could not show download dialog: " + e.getMessage());
            return;
        }

        downloader = new ApkDownloader(requireContext());
        downloader.start(info, new ApkDownloader.Callback() {
            @Override
            public void onReady(File apk) {
                dismissProgress();
                pendingApk = apk;
                attemptInstall(apk);
            }

            @Override
            public void onError(String message) {
                dismissProgress();
                if (!isAdded()) return;
                // Bring the release-notes dialog back so the user can retry / cancel.
                Dialog host = getDialog();
                if (host != null && !host.isShowing()) host.show();
                try {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Download failed")
                            .setMessage(message != null ? message : "Unknown error")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to show error dialog", e);
                }
            }

            @Override
            public void onProgress(int percent) {
                // CRITICAL: guard against detached fragment. Without this guard the dialog
                // raced with screen rotation / activity destruction at 1% and crashed the app.
                if (!isAdded() || progressDialog == null || !progressDialog.isShowing()) return;
                try {
                    if (progressBar != null) {
                        progressBar.setIndeterminate(false);
                        progressBar.setProgress(percent);
                    }
                    if (progressMessage != null) {
                        progressMessage.setText(String.format(Locale.US, "%d%%", percent));
                    }
                    if (progressDetail != null && info != null && info.sizeBytes > 0) {
                        long done = (long) ((info.sizeBytes / 100.0) * percent);
                        progressDetail.setText(String.format(Locale.US, "%s / %s",
                                Formatter.formatShortFileSize(requireContext(), done),
                                Formatter.formatShortFileSize(requireContext(), info.sizeBytes)));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onProgress UI update failed", e);
                }
            }
        });
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try {
                if (progressDialog.isShowing()) progressDialog.dismiss();
            } catch (Exception e) {
                Log.w(TAG, "dismissProgress", e);
            }
            progressDialog = null;
        }
        progressBar = null;
        progressMessage = null;
        progressDetail = null;
    }

    private void attemptInstall(File apk) {
        if (!isAdded()) {
            // Even if the dialog is detached, hand off to system installer if possible — the user
            // initiated the update and we've already verified the APK.
            try {
                if (ApkInstaller.canInstall(requireActivity())) {
                    ApkInstaller.install(requireActivity(), apk);
                }
            } catch (Exception ignored) {}
            return;
        }
        try {
            if (!ApkInstaller.canInstall(requireContext())) {
                showPostDownloadPermissionPrompt();
                return;
            }
            ApkInstaller.install(requireContext(), apk);
        } catch (Exception e) {
            Log.e(TAG, "Install intent failed", e);
            try {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Install failed")
                        .setMessage("Could not start installer: " + e.getMessage()
                                + "\n\nThe APK is downloaded at:\n" + apk.getAbsolutePath()
                                + "\n\nYou can open it manually with a file manager.")
                        .setPositiveButton("Retry", (d, w) -> attemptInstall(apk))
                        .setNegativeButton("Close", null)
                        .show();
            } catch (Exception inner) {
                Log.e(TAG, "Failed to show install-failure dialog", inner);
                toastSafe("Install failed: " + e.getMessage());
            }
        }
    }

    private void showPostDownloadPermissionPrompt() {
        if (!isAdded()) return;
        try {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Permission needed")
                    .setMessage("Download finished. Now allow \"Install unknown apps\" to complete the update.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        try {
                            ApkInstaller.requestInstallPermission(requireContext());
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "showPostDownloadPermissionPrompt failed", e);
        }
    }

    private void dismissSafe() {
        try { dismiss(); } catch (Exception ignored) {}
    }

    private void toastSafe(String msg) {
        if (!isAdded()) return;
        try {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        // If user came back from the install-permission settings page and the APK is already
        // downloaded, retry the install automatically.
        if (pendingApk != null && pendingApk.exists() && isAdded()
                && ApkInstaller.canInstall(requireContext())) {
            attemptInstall(pendingApk);
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        if (downloader != null) downloader.cancel();
        super.onCancel(dialog);
    }

    @Override
    public void onDestroyView() {
        dismissProgress();
        super.onDestroyView();
    }
}
