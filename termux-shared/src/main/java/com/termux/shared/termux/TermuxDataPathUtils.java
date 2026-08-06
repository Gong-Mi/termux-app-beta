package com.termux.shared.termux;

/**
 * Utilities for resolving Termux data paths in a multi-user aware way.
 *
 * <p>On Android, app uids are encoded as {@code userId * 100000 + appId}. The primary user
 * (userId 0) stores app private data under the legacy {@code /data/data/<packageName>}
 * location, while secondary users use the {@code /data/user/<userId>/<packageName>}
 * location.</p>
 */
public final class TermuxDataPathUtils {

    private TermuxDataPathUtils() {}

    /**
     * Get the internal private app data directory path for the given uid.
     *
     * @param packageName the app package name, e.g. {@code com.termux}.
     * @param uid the app process uid, e.g. {@code android.os.Process.myUid()}.
     * @return the resolved data directory path.
     */
    public static String getInternalPrivateAppDataDirPath(String packageName, int uid) {
        int userId = uid / 100000;
        if (userId == 0)
            return "/data/data/" + packageName; // Default: "/data/data/com.termux"
        else
            return "/data/user/" + userId + "/" + packageName;
    }

}
