package com.example.radarhumanapplication.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Hands the verified APK off to the system package installer. On API 26+ the user must have
 * granted "install unknown apps" for this app — we surface the system settings screen when they
 * have not.
 */
public final class ApkInstaller {

    private static final String TAG = "ApkInstaller";

    private ApkInstaller() {}

    public static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    public static void requestInstallPermission(Context ctx) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    public static void install(Context ctx, File apk) {
        Log.i(TAG, "Installing APK: " + apk.getAbsolutePath() + " (" + apk.length() + " bytes)");
        Uri uri = FileProvider.getUriForFile(
                ctx,
                ctx.getPackageName() + ".fileprovider",
                apk);
        Log.i(TAG, "FileProvider URI: " + uri);

        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (intent.resolveActivity(ctx.getPackageManager()) == null) {
            Log.w(TAG, "ACTION_VIEW for APK has no handler, trying ACTION_INSTALL_PACKAGE");
            intent.setAction(Intent.ACTION_INSTALL_PACKAGE);
            if (intent.resolveActivity(ctx.getPackageManager()) == null) {
                throw new IllegalStateException("No package installer found on this device");
            }
        }

        ctx.startActivity(intent);
        Log.i(TAG, "Install intent dispatched");
    }
}
