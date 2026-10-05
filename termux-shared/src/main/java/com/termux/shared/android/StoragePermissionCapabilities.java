package com.termux.shared.android;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

/**
 * Single capability-resolution rule for storage permissions, shared by
 * {@link PermissionUtils} and the app-side permission UI coordinator.
 *
 * The decision is split per permission capability, not per Android version
 * module: API level only determines <em>applicability</em> of each capability
 * via {@link LegacyExternalStoragePermission} and
 * {@link ManageExternalStoragePermission}; this class only picks which
 * applicable capability (if any) the current call should use, given the
 * caller's preference for all-files access.
 *
 * {@link StoragePermissionCapability#NONE} is explicitly not "granted": a
 * device where no storage permission capability applies must not run
 * storage-touching flows as if permission were held.
 */
public final class StoragePermissionCapabilities {

    public enum StoragePermissionCapability {
        /** Below API 23 there are no runtime permissions; treat as granted. */
        PLATFORM_GRANTED,
        /** Legacy {@code READ}/{@code WRITE}_EXTERNAL_STORAGE pair. */
        LEGACY,
        /** {@code MANAGE_EXTERNAL_STORAGE} all-files access. */
        MANAGE_EXTERNAL_STORAGE,
        /** No storage permission capability applies on this platform. */
        NONE
    }

    private StoragePermissionCapabilities() {
    }

    /**
     * Resolve which storage permission capability the call should use.
     *
     * @param deviceSdk The device's API level.
     * @param preferManageExternalStorage Whether the caller prefers
     * all-files access where that capability exists.
     */
    public static StoragePermissionCapability resolve(int deviceSdk, boolean preferManageExternalStorage) {
        return resolve(deviceSdk, preferManageExternalStorage,
            ManageExternalStoragePermission.isApplicable(deviceSdk),
            LegacyExternalStoragePermission.isApplicable(deviceSdk));
    }

    /**
     * Pure resolution core, separated from applicability lookups so the full
     * truth table can be unit tested without Android dependencies.
     *
     * @param manageApplicable Precomputed {@link ManageExternalStoragePermission#isApplicable(int)}.
     * @param legacyApplicable Precomputed {@link LegacyExternalStoragePermission#isApplicable(int)}.
     */
    public static StoragePermissionCapability resolve(int deviceSdk, boolean preferManageExternalStorage,
                                                      boolean manageApplicable, boolean legacyApplicable) {
        if (deviceSdk < Build.VERSION_CODES.M)
            return StoragePermissionCapability.PLATFORM_GRANTED;

        if (manageApplicable && (preferManageExternalStorage || !legacyApplicable))
            return StoragePermissionCapability.MANAGE_EXTERNAL_STORAGE;

        if (legacyApplicable)
            return StoragePermissionCapability.LEGACY;

        return StoragePermissionCapability.NONE;
    }

    /**
     * Check whether the resolved capability is granted. {@code PLATFORM_GRANTED}
     * is granted by definition; {@code NONE} is never granted.
     */
    public static boolean isGranted(@NonNull Context context, @NonNull StoragePermissionCapability capability) {
        switch (capability) {
            case PLATFORM_GRANTED:
                return true;
            case LEGACY:
                return LegacyExternalStoragePermission.isGranted(context);
            case MANAGE_EXTERNAL_STORAGE:
                return ManageExternalStoragePermission.isGranted(context);
            case NONE:
            default:
                return false;
        }
    }
}
