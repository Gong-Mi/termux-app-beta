package com.termux.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

/** Runtime contract for the release-derived, test-signed verification variant. */
@RunWith(AndroidJUnit4.class)
public class VerificationBuildTypeInstrumentedTest {
    @Test
    public void verificationVariantIsNonDebuggableAndKeepsTermuxPackageIdentity() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assertEquals("com.termux", target.getPackageName());
        assertEquals("verification APK must not be debuggable", 0,
            target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE);
    }
}
