package com.termux.shared.android;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

/** Capability descriptor for the legacy READ/WRITE external-storage permission pair. */
public final class LegacyExternalStoragePermission {

    private LegacyExternalStoragePermission() {}

    public static boolean isApplicable(int deviceSdk) {
        return deviceSdk >= Build.VERSION_CODES.M && deviceSdk <= Build.VERSION_CODES.S_V2;
    }

    @NonNull
    public static String[] manifestPermissions() {
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    public static boolean isGranted(@NonNull Context context) {
        return PermissionUtils.checkPermissions(context, manifestPermissions());
    }
}
