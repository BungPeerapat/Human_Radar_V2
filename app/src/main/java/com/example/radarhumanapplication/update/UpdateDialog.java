package com.example.radarhumanapplication.update;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.example.radarhumanapplication.BuildConfig;
import com.google.gson.Gson;

import java.io.File;

/**
 * Shows release notes + Update / Later / Skip buttons. Manages the download and install handoff
 * end-to-end. Hosting Activity does not need to plumb anything beyond calling
 * {@link #show(FragmentManager, UpdateInfo)}.
 */
public class UpdateDialog extends DialogFragment {

    private static final String TAG = "UpdateDialog";
    private static final String ARG_INFO = "info";

    private UpdateInfo info;
    private ApkDownloader downloader;
    private ProgressDialog progress;
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
                .setPositiveButton("Update", (d, w) -> startDownload());

        if (!info.mandatory) {
            b.setNegativeButton("Later", (d, w) -> dismiss());
            b.setNeutralButton("Skip this version", (d, w) -> {
                UpdateManager.getInstance().skipVersion(requireContext(), info.versionCode);
                dismiss();
            });
            setCancelable(true);
        } else {
            setCancelable(false);
        }
        Dialog dialog = b.create();
        dialog.setCanceledOnTouchOutside(!info.mandatory);
        return dialog;
    }

    private void startDownload() {
        if (!ApkInstaller.canInstall(requireContext())) {
            showPermissionExplainer();
            return;
        }
        beginDownload();
    }

    private void showPermissionExplainer() {
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
                        Toast.makeText(requireContext(),
                                "Open Settings → Apps → Human Radar → Install unknown apps → Allow",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void beginDownload() {
        progress = new ProgressDialog(requireContext());
        progress.setTitle("Downloading update");
        progress.setMessage("Starting…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(true);
        progress.setOnCancelListener(d -> {
            if (downloader != null) downloader.cancel();
        });
        progress.show();

        downloader = new ApkDownloader(requireContext());
        downloader.start(info, new ApkDownloader.Callback() {
            @Override
            public void onReady(File apk) {
                if (progress != null) progress.dismiss();
                pendingApk = apk;
                attemptInstall(apk);
            }

            @Override
            public void onError(String message) {
                if (progress != null) progress.dismiss();
                new AlertDialog.Builder(requireContext())
                        .setTitle("Download failed")
                        .setMessage(message != null ? message : "Unknown error")
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onProgress(int percent) {
                if (progress != null) {
                    progress.setProgress(percent);
                    progress.setMessage(percent + "%");
                }
            }
        });
    }

    private void attemptInstall(File apk) {
        if (!ApkInstaller.canInstall(requireContext())) {
            // Permission was revoked between download and install — re-prompt
            showPostDownloadPermissionPrompt();
            return;
        }
        try {
            ApkInstaller.install(requireContext(), apk);
            // Don't dismiss the dialog — user might come back if install is cancelled
        } catch (Exception e) {
            Log.e(TAG, "Install intent failed", e);
            new AlertDialog.Builder(requireContext())
                    .setTitle("Install failed")
                    .setMessage("Could not start installer: " + e.getMessage()
                            + "\n\nThe APK is downloaded at:\n" + apk.getAbsolutePath()
                            + "\n\nYou can open it manually with a file manager.")
                    .setPositiveButton("Retry", (d, w) -> attemptInstall(apk))
                    .setNegativeButton("Close", null)
                    .show();
        }
    }

    private void showPostDownloadPermissionPrompt() {
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
    }

    @Override
    public void onResume() {
        super.onResume();
        // If user came back from the install-permission settings page and the APK is already
        // downloaded, retry the install automatically.
        if (pendingApk != null && pendingApk.exists() && ApkInstaller.canInstall(requireContext())) {
            attemptInstall(pendingApk);
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        if (downloader != null) downloader.cancel();
        super.onCancel(dialog);
    }
}
