package com.termux.shared.termux;

import org.junit.Assert;
import org.junit.Test;

public class TermuxDataPathUtilsTest {

    private static final String PACKAGE_NAME = "com.termux";

    @Test
    public void testPrimaryUserUidResolvesToLegacyDataDirPath() {
        // userId 0 (primary user): uids < 100000 keep the legacy /data/data location.
        Assert.assertEquals("/data/data/com.termux",
            TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, 0));
        // Typical app uid of the current/primary user (appId 261).
        Assert.assertEquals("/data/data/com.termux",
            TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, 10261));
        // Largest uid still belonging to userId 0.
        Assert.assertEquals("/data/data/com.termux",
            TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, 99999));
    }

    @Test
    public void testSecondaryUserUidsResolveToUserScopedDataDirPath() {
        Assert.assertEquals("/data/user/1/com.termux",
            TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, 100000));
        Assert.assertEquals("/data/user/10/com.termux",
            TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, 1010261));
        Assert.assertEquals("/data/user/15/com.termux",
            TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, 1599999));
    }

    @Test
    public void testDifferentUidsResolveToDifferentDataPaths() {
        String primaryUserPath = TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, 10261);
        String secondaryUserPath = TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, 1010261);

        Assert.assertNotEquals(primaryUserPath, secondaryUserPath);
        // The primary user path must stay the legacy /data/data form, not /data/user/0.
        Assert.assertNotEquals("/data/user/0/com.termux", primaryUserPath);
        Assert.assertNotEquals("/data/data/com.termux", secondaryUserPath);
    }

    @Test
    public void testResolutionMatchesUidToUserIdFormula() {
        // The mapping must stay consistent with uid = userId * 100000 + appId for any uid.
        for (int uid = 0; uid < 2_000_000; uid += 137) {
            int userId = uid / 100000;
            String expectedPath = userId == 0
                ? "/data/data/" + PACKAGE_NAME
                : "/data/user/" + userId + "/" + PACKAGE_NAME;
            Assert.assertEquals(expectedPath,
                TermuxDataPathUtils.getInternalPrivateAppDataDirPath(PACKAGE_NAME, uid));
        }
    }

}
