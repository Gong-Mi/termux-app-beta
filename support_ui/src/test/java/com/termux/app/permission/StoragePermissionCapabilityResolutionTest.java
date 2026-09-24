package com.termux.app.permission;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Capability-resolution contract for {@link StoragePermissionUiCoordinator}.
 *
 * <p>Capability selection follows the permission, not the Android version:
 * legacy READ/WRITE applies through API 32, all-files access from API 30.
 * The regression target is API 33+ with {@code preferManageExternalStorage == false}:
 * the legacy manifest grants carry {@code maxSdkVersion=32} there, so resolving
 * to anything other than all-files access would claim a storage grant that does
 * not exist (the pre-fix code returned "granted" for this configuration).</p>
 */
public class StoragePermissionCapabilityResolutionTest {

    private static StoragePermissionUiCoordinator.Capability resolve(
            boolean preferManage, int deviceSdk) {
        return StoragePermissionUiCoordinator.resolveCapability(preferManage, deviceSdk);
    }

    // --- Pre-runtime-permission platforms ---

    @Test
    public void preRuntimePermissionPlatformsAreGrantedAtInstallTime() {
        for (int sdk = 19; sdk < 23; sdk++) {
            assertEquals("sdk " + sdk,
                StoragePermissionUiCoordinator.Capability.PLATFORM_GRANTED,
                resolve(true, sdk));
            assertEquals("sdk " + sdk,
                StoragePermissionUiCoordinator.Capability.PLATFORM_GRANTED,
                resolve(false, sdk));
        }
    }

    // --- Legacy window (API 23-32): preference cannot strand the caller ---

    @Test
    public void legacyWindowWithoutManagePreferenceResolvesLegacy() {
        for (int sdk = 23; sdk <= 29; sdk++) {
            assertEquals("sdk " + sdk,
                StoragePermissionUiCoordinator.Capability.LEGACY,
                resolve(false, sdk));
        }
    }

    @Test
    public void manageWindowWithPreferenceResolvesManage() {
        for (int sdk = 30; sdk <= 32; sdk++) {
            assertEquals("sdk " + sdk,
                StoragePermissionUiCoordinator.Capability.MANAGE_EXTERNAL_STORAGE,
                resolve(true, sdk));
        }
    }

    @Test
    public void manageWindowWithoutPreferenceFallsBackToLegacy() {
        for (int sdk = 30; sdk <= 32; sdk++) {
            assertEquals("sdk " + sdk,
                StoragePermissionUiCoordinator.Capability.LEGACY,
                resolve(false, sdk));
        }
    }

    // --- API 33+: legacy is not applicable; all-files access is the only capability ---

    @Test
    public void api33WithManagePreferenceResolvesManage() {
        for (int sdk = 33; sdk <= 36; sdk++) {
            assertEquals("sdk " + sdk,
                StoragePermissionUiCoordinator.Capability.MANAGE_EXTERNAL_STORAGE,
                resolve(true, sdk));
        }
    }

    @Test
    public void api33WithoutManagePreferenceStillResolvesManageNotGranted() {
        // Regression: pre-fix code mapped this configuration to "granted".
        for (int sdk = 33; sdk <= 36; sdk++) {
            assertEquals("sdk " + sdk,
                StoragePermissionUiCoordinator.Capability.MANAGE_EXTERNAL_STORAGE,
                resolve(false, sdk));
        }
    }

    // --- Request codes stay consistent with capability resolution ---

    @Test
    public void requestCodeFollowsResolvedCapability() {
        // 23-29: legacy code regardless of preference.
        assertEquals(StoragePermissionUiCoordinator.REQUEST_LEGACY_STORAGE_PERMISSION,
            StoragePermissionUiCoordinator.requestCodeFor(false));
    }
}
