package com.termux.shared.android;

import android.os.Build;

/**
 * Selects the storage permission route for the Android version policy used by
 * the Termux application. This class deliberately contains no Context,
 * manifest, or UI access so the version boundary can be tested independently
 * from Android permission request mechanics.
 */
public final class StoragePermissionPolicy {

    public enum Route {
        NONE,
        LEGACY_RUNTIME,
        MANAGE_ALL_FILES
    }

    private StoragePermissionPolicy() {}

    /**
     * API 32 and below use the manifest's legacy READ/WRITE route. API 33 and
     * above use the all-files access settings route. This boundary is part of
     * the app's targetSdk-28 compatibility policy and must not be inferred
     * from the presence of a manifest permission at runtime.
     */
    public static Route routeForDeviceSdk(int deviceSdk) {
        if (deviceSdk < Build.VERSION_CODES.M)
            return Route.NONE;
        if (deviceSdk <= Build.VERSION_CODES.S_V2)
            return Route.LEGACY_RUNTIME;
        return Route.MANAGE_ALL_FILES;
    }

    public static boolean usesLegacyRuntimePermissions(int deviceSdk) {
        return routeForDeviceSdk(deviceSdk) == Route.LEGACY_RUNTIME;
    }

    public static boolean usesManageAllFilesSettings(int deviceSdk) {
        return routeForDeviceSdk(deviceSdk) == Route.MANAGE_ALL_FILES;
    }
}
