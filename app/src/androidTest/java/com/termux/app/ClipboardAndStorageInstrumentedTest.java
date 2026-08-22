package com.termux.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.content.ContextCompat;

import com.termux.shared.interact.ShareUtils;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ClipboardAndStorageInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void clipboardCopyRoundTripsThroughShareUtils() {
        String text = "termux-clipboard-regression-" + System.nanoTime();
        ShareUtils.copyTextToClipboard(context, text);

        String actual = ShareUtils.getTextStringFromClipboardIfSet(context, false);
        assertEquals(text, actual);
    }

    @Test
    public void manifestContainsIndependentStorageCapabilities() {
        PackageInfo info = context.getPackageManager().getPackageInfo(
            context.getPackageName(), PackageInfo.GET_PERMISSIONS);
        assertNotNull(info.requestedPermissions);
        java.util.List<String> permissions = java.util.Arrays.asList(info.requestedPermissions);
        assertTrue(permissions.contains("android.permission.READ_EXTERNAL_STORAGE"));
        assertTrue(permissions.contains("android.permission.WRITE_EXTERNAL_STORAGE"));
        assertTrue(permissions.contains("android.permission.MANAGE_EXTERNAL_STORAGE"));
    }

    @Test
    public void apiSelectsExpectedStorageCapability() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertTrue(Environment.isExternalStorageManager());
        } else {
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context,
                    "android.permission.READ_EXTERNAL_STORAGE"));
        }
    }

    @Test
    public void mediaStoreDownloadRoundTripWorksOnModernAndroid() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        String name = "termux-media-regression-" + System.nanoTime() + ".txt";
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        android.net.Uri uri = context.getContentResolver().insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        try {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                assertNotNull(output);
                output.write("media-store-regression".getBytes(StandardCharsets.UTF_8));
            }
            android.database.Cursor cursor = context.getContentResolver().query(
                uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            assertNotNull(cursor);
            try (android.database.Cursor ignored = cursor) {
                assertTrue(cursor.moveToFirst());
                assertEquals(name, cursor.getString(0));
            }
        } finally {
            context.getContentResolver().delete(uri, null, null);
        }
    }
}