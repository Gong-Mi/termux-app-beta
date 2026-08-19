package com.termux.app.permission;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.shared.android.ManageExternalStoragePermission;
import com.termux.shared.android.PermissionUtils;

/** UI-facing MANAGE_EXTERNAL_STORAGE settings capability. */
public final class ManageExternalStoragePermissionUi {

    private ManageExternalStoragePermissionUi() {}

    public static boolean isApplicable() {
        return ManageExternalStoragePermission.isApplicable(Build.VERSION.SDK_INT);
    }

    public static boolean isGranted(@NonNull Context context) {
        return ManageExternalStoragePermission.isGranted(context);
    }

    public static boolean request(@NonNull Context context, int requestCode) {
        return PermissionUtils.requestManageStorageExternalPermission(context, requestCode) == null;
    }
}
