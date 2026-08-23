package com.termux.shared.android;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import androidx.annotation.NonNull;

/** Capability descriptor for Android's all-files access permission. */
public final class ManageExternalStoragePermission {

    private ManageExternalStoragePermission() {}

    public static boolean isApplicable(int deviceSdk) {
        return deviceSdk >= Build.VERSION_CODES.R;
    }

    public static boolean isGranted(@NonNull Context context) {
        return Environment.isExternalStorageManager();
    }
}
