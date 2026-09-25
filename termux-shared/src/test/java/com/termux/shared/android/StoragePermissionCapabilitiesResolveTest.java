package com.termux.shared.android;

import org.junit.Test;

import static com.termux.shared.android.StoragePermissionCapabilities.StoragePermissionCapability.LEGACY;
import static com.termux.shared.android.StoragePermissionCapabilities.StoragePermissionCapability.MANAGE_EXTERNAL_STORAGE;
import static com.termux.shared.android.StoragePermissionCapabilities.StoragePermissionCapability.NONE;
import static com.termux.shared.android.StoragePermissionCapabilities.StoragePermissionCapability.PLATFORM_GRANTED;
import static org.junit.Assert.assertEquals;

/**
 * Truth table for {@link StoragePermissionCapabilities#resolve(int, boolean)}.
 *
 * Regression context: storage-permission capability selection was duplicated
 * between PermissionUtils and StoragePermissionUiCoordinator with different
 * rules, so API-level behaviour drifted (e.g. the coordinator treated
 * "no applicable capability" as granted on API 33+ with
 * preferManage=false, while PermissionUtils resolved MANAGE for the same
 * inputs). This table pins the single resolution rule both paths delegate to.
 *
 * Windows: sdk < 23 -> PLATFORM_GRANTED; legacy pair applicable 23..32;
 * MANAGE_EXTERNAL_STORAGE applicable 30+.
 */
public class StoragePermissionCapabilitiesResolveTest {

    @Test
    public void belowRuntimePermissionsPlatformIsGrantedWithoutCheck() {
        for (int sdk = 21; sdk <= 22; sdk++) {
            assertEquals("sdk=" + sdk, PLATFORM_GRANTED,
                StoragePermissionCapabilities.resolve(sdk, true));
            assertEquals("sdk=" + sdk, PLATFORM_GRANTED,
                StoragePermissionCapabilities.resolve(sdk, false));
        }
    }

    @Test
    public void legacyWindowResolvesLegacyWhenManageNotPreferred() {
        for (int sdk = 23; sdk <= 29; sdk++) {
            assertEquals("sdk=" + sdk, LEGACY,
                StoragePermissionCapabilities.resolve(sdk, false));
        }
        // 30-32 both capabilities exist; preference decides.
        for (int sdk = 30; sdk <= 32; sdk++) {
            assertEquals("sdk=" + sdk, LEGACY,
                StoragePermissionCapabilities.resolve(sdk, false));
        }
    }

    @Test
    public void managePreferredResolvesManageFromApi30() {
        for (int sdk = 30; sdk <= 35; sdk++) {
            assertEquals("sdk=" + sdk, MANAGE_EXTERNAL_STORAGE,
                StoragePermissionCapabilities.resolve(sdk, true));
        }
    }

    @Test
    public void aboveLegacyWindowManageIsTheOnlyRemainingCapability() {
        // Legacy pair not applicable on 33+ (manifest grants carry
        // maxSdkVersion=32); MANAGE must still resolve so that
        // "no capability" is never conflated with "granted".
        for (int sdk = 33; sdk <= 35; sdk++) {
            assertEquals("sdk=" + sdk, MANAGE_EXTERNAL_STORAGE,
                StoragePermissionCapabilities.resolve(sdk, false));
        }
    }

    @Test
    public void noApplicableCapabilityResolvesToNoneNotGranted() {
        // 23+ always has at least one capability today; NONE is reserved
        // for windows where neither applies (e.g. a future API range where
        // MANAGE is replaced). Guard the invariant explicitly.
        assertEquals(NONE, StoragePermissionCapabilities.resolve(29, false, false, false));
    }
}
