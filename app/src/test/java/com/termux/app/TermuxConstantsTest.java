package com.termux.app;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class TermuxConstantsTest {

    @Test
    public void testPrefixIgnoredPathsAreInitialized() {
        Assert.assertEquals(Arrays.asList(
                TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH,
                TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
                TermuxConstants.TERMUX_ENV_FILE_PATH),
            TermuxConstants.TERMUX_PREFIX_DIR_IGNORED_SUB_FILES_PATHS_TO_CONSIDER_AS_EMPTY);
        Assert.assertFalse(TermuxConstants.TERMUX_PREFIX_DIR_IGNORED_SUB_FILES_PATHS_TO_CONSIDER_AS_EMPTY.contains(null));
    }
}
