package com.termux.app.permission;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.supportui.R;
import com.termux.shared.android.LegacyExternalStoragePermission;
import com.termux.shared.android.ManageExternalStoragePermission;
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
        return resolveCapability(preferManageExternalStorage) == Capability.MANAGE_EXTERNAL_STORAGE
            ? REQUEST_MANAGE_EXTERNAL_STORAGE_PERMISSION
            : REQUEST_LEGACY_STORAGE_PERMISSION;
    }

    /** Storage-permission capability that applies on this platform. */
    enum Capability {
        /** No runtime permission concept: storage is granted at install time (Android < 6). */
        PLATFORM_GRANTED,
        /** Legacy READ/WRITE external storage runtime permission pair (API 23-32). */
        LEGACY,
        /** All-files access via MANAGE_EXTERNAL_STORAGE (API 30+). */
        MANAGE_EXTERNAL_STORAGE
    }

    /**
     * Resolve the storage-permission capability for this platform and caller preference.
     *
     * <p>Capability selection follows the permission, not the Android version: legacy
     * READ/WRITE applies through API 32, all-files access from API 30. On API 33+,
     * {@code preferManageExternalStorage == false} must still resolve to
     * {@link Capability#MANAGE_EXTERNAL_STORAGE}: the legacy manifest grants carry
     * {@code maxSdkVersion=32} there, so reporting anything else would claim a storage
     * grant that does not exist. Only pre-runtime-permission platforms resolve to
     * {@link Capability#PLATFORM_GRANTED}.</p>
     */
    private static Capability resolveCapability(boolean preferManageExternalStorage) {
        return resolveCapability(preferManageExternalStorage, Build.VERSION.SDK_INT);
    }

    static Capability resolveCapability(boolean preferManageExternalStorage, int deviceSdk) {
        if (deviceSdk < Build.VERSION_CODES.M)
            return Capability.PLATFORM_GRANTED;
        boolean manage = preferManageExternalStorage
            && ManageExternalStoragePermission.isApplicable(deviceSdk);
        boolean legacy = !manage && LegacyExternalStoragePermission.isApplicable(deviceSdk);
        if (manage)
            return Capability.MANAGE_EXTERNAL_STORAGE;
        if (legacy)
            return Capability.LEGACY;
        // API 33+ with legacy not applicable: all-files access is the only capability.
        return Capability.MANAGE_EXTERNAL_STORAGE;
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
        Capability capability = resolveCapability(preferManageExternalStorage);
        boolean manage = capability == Capability.MANAGE_EXTERNAL_STORAGE;
        boolean legacy = capability == Capability.LEGACY;

        if (capability == Capability.PLATFORM_GRANTED)
            // Storage is an install-time permission below API 23; nothing to check.
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

        String message = context.getString(R.string.msg_storage_permission_ui_not_granted);
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
