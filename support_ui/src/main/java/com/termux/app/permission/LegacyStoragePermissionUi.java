package com.termux.app.permission;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.shared.android.LegacyExternalStoragePermission;
import com.termux.shared.android.PermissionUtils;

/** UI-facing legacy READ/WRITE permission capability. */
public final class LegacyStoragePermissionUi {

    private LegacyStoragePermissionUi() {}

    public static boolean isApplicable() {
        return LegacyExternalStoragePermission.isApplicable(Build.VERSION.SDK_INT);
    }

    public static boolean isGranted(@NonNull Context context) {
        return LegacyExternalStoragePermission.isGranted(context);
    }

    public static boolean request(@NonNull Context context, int requestCode) {
        return PermissionUtils.requestLegacyStorageExternalPermission(context, requestCode);
    }
}
