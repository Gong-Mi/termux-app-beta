package com.termux.shared.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StoragePermissionCapabilityTest {

    @Test
    public void legacyPermissionHasItsOwnApplicabilityBoundary() {
        assertFalse(LegacyExternalStoragePermission.isApplicable(22));
        assertTrue(LegacyExternalStoragePermission.isApplicable(23));
        assertTrue(LegacyExternalStoragePermission.isApplicable(32));
        assertFalse(LegacyExternalStoragePermission.isApplicable(33));
    }

    @Test
    public void managePermissionHasItsOwnApplicabilityBoundary() {
        assertFalse(ManageExternalStoragePermission.isApplicable(29));
        assertTrue(ManageExternalStoragePermission.isApplicable(30));
        assertTrue(ManageExternalStoragePermission.isApplicable(32));
        assertTrue(ManageExternalStoragePermission.isApplicable(33));
    }
}
