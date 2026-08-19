package com.termux.app.permission;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.shared.R;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;

/** Coordinates independent storage-permission UI capabilities without making API versions modules. */
public final class StoragePermissionUiCoordinator {

    public static final int REQUEST_LEGACY_STORAGE_PERMISSION =
        PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;
    public static final int REQUEST_MANAGE_EXTERNAL_STORAGE_PERMISSION =
        PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION + 1;

    private static final String LOG_TAG = "StoragePermissionUi";

    private StoragePermissionUiCoordinator() {}

    public static int requestCodeFor(boolean preferManageExternalStorage) {
        return preferManageExternalStorage && ManageExternalStoragePermissionUi.isApplicable()
            ? REQUEST_MANAGE_EXTERNAL_STORAGE_PERMISSION
            : REQUEST_LEGACY_STORAGE_PERMISSION;
    }

    /**
     * Check the selected permission capability and launch its user-facing
     * request if needed. The caller may prefer all-files access on platforms
     * where that permission capability exists; this is a capability choice,
     * not a separate Android-version UI module.
     */
    public static boolean checkAndRequest(@NonNull Context context, int requestCode,
                                          boolean preferManageExternalStorage,
                                          boolean showErrorMessage) {
        boolean manage = preferManageExternalStorage && ManageExternalStoragePermissionUi.isApplicable();
        boolean legacy = !manage && LegacyStoragePermissionUi.isApplicable();

        if (!manage && !legacy)
            return true;

        if (legacy && PermissionUtils.checkIfHasRequestedLegacyExternalStorage(context) &&
            !PermissionUtils.hasRequestedLegacyExternalStorage(context, showErrorMessage)) {
            return false;
        }

        boolean granted = manage
            ? ManageExternalStoragePermissionUi.isGranted(context)
            : LegacyStoragePermissionUi.isGranted(context);
        if (granted)
            return true;

        String message = context.getString(R.string.msg_storage_permission_not_granted);
        Logger.logError(LOG_TAG, message);
        if (showErrorMessage)
            Logger.showToast(context, message, false);

        if (requestCode < 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return false;

        if (manage)
            ManageExternalStoragePermissionUi.request(context, requestCode);
        else
            LegacyStoragePermissionUi.request(context, requestCode);
        return false;
    }
}
