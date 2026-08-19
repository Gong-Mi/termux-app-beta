package com.termux.shared.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StoragePermissionPolicyTest {

    @Test
    public void selectsLegacyRouteThroughApi32() {
        assertEquals(StoragePermissionPolicy.Route.NONE,
            StoragePermissionPolicy.routeForDeviceSdk(22));
        assertEquals(StoragePermissionPolicy.Route.LEGACY_RUNTIME,
            StoragePermissionPolicy.routeForDeviceSdk(23));
        assertEquals(StoragePermissionPolicy.Route.LEGACY_RUNTIME,
            StoragePermissionPolicy.routeForDeviceSdk(30));
        assertEquals(StoragePermissionPolicy.Route.LEGACY_RUNTIME,
            StoragePermissionPolicy.routeForDeviceSdk(32));
        assertTrue(StoragePermissionPolicy.usesLegacyRuntimePermissions(32));
        assertFalse(StoragePermissionPolicy.usesManageAllFilesSettings(32));
    }

    @Test
    public void selectsManageAllFilesRouteFromApi33() {
        assertEquals(StoragePermissionPolicy.Route.MANAGE_ALL_FILES,
            StoragePermissionPolicy.routeForDeviceSdk(33));
        assertEquals(StoragePermissionPolicy.Route.MANAGE_ALL_FILES,
            StoragePermissionPolicy.routeForDeviceSdk(34));
        assertEquals(StoragePermissionPolicy.Route.MANAGE_ALL_FILES,
            StoragePermissionPolicy.routeForDeviceSdk(36));
        assertFalse(StoragePermissionPolicy.usesLegacyRuntimePermissions(33));
        assertTrue(StoragePermissionPolicy.usesManageAllFilesSettings(33));
    }
}
