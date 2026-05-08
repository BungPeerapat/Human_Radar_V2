package com.example.radarhumanapplication.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Hands the verified APK off to the system package installer. On API 26+ the user must have
 * granted "install unknown apps" for this app — we surface the system settings screen when they
 * have not.
 */
public final class ApkInstaller {

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
        Uri uri = FileProvider.getUriForFile(
                ctx,
                ctx.getPackageName() + ".fileprovider",
                apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }
}
