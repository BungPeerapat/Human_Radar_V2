package com.example.radarhumanapplication.update;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.format.Formatter;
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
            Toast.makeText(requireContext(),
                    "Allow install from this app, then tap Update again",
                    Toast.LENGTH_LONG).show();
            ApkInstaller.requestInstallPermission(requireContext());
            return;
        }
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
                ApkInstaller.install(requireContext(), apk);
            }

            @Override
            public void onError(String message) {
                if (progress != null) progress.dismiss();
                Toast.makeText(requireContext(), "Update failed: " + message, Toast.LENGTH_LONG).show();
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

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        if (downloader != null) downloader.cancel();
        super.onCancel(dialog);
    }
}
