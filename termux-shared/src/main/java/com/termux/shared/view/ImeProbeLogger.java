package com.termux.shared.view;

import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostic probe for IME overlap issue.
 * Writes structured state logs to /sdcard/ime_probe.log with 1GB circular buffer.
 *
 * Log format: [timestamp] [event] key=value key=value ...
 * Events: LAYOUT, MEASURE, INSETS, LIFECYCLE, KEYBOARD, MARGIN
 */
public final class ImeProbeLogger {

    private static final String TAG = "ImeProbe";
    private static final String LOG_FILE = "ime_probe.log";
    private static final long MAX_LOG_SIZE = 1L * 1024 * 1024 * 1024; // 1GB
    private static final int HEADER_SIZE = 16; // Magic + version + head position

    private static final byte[] MAGIC = {(byte) 0x49, (byte) 0x4D, (byte) 0x45, (byte) 0x50}; // "IMEP"
    private static final int VERSION = 1;

    private static volatile ImeProbeLogger sInstance;
    private final Object mLock = new Object();
    private RandomAccessFile mFile;
    private long mWritePos;
    private boolean mEnabled;
    private final SimpleDateFormat mTimeFormat;

    private ImeProbeLogger() {
        mTimeFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
        mEnabled = false;
    }

    public static ImeProbeLogger getInstance() {
        if (sInstance == null) {
            synchronized (ImeProbeLogger.class) {
                if (sInstance == null) {
                    sInstance = new ImeProbeLogger();
                }
            }
        }
        return sInstance;
    }

    /**
     * Initialize the log file. Call once from Application.onCreate() or Activity.onCreate().
     */
    public void init() {
        synchronized (mLock) {
            if (mFile != null) return;
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "Cannot create log directory: " + dir);
                    return;
                }
                File logFile = new File(dir, LOG_FILE);
                boolean isNew = !logFile.exists() || logFile.length() == 0;

                mFile = new RandomAccessFile(logFile, "rw");
                if (isNew) {
                    // Initialize with header
                    mFile.setLength(0);
                    mFile.write(MAGIC);
                    mFile.writeInt(VERSION);
                    mFile.writeLong(HEADER_SIZE); // write position
                    mWritePos = HEADER_SIZE;
                } else {
                    // Read existing header
                    byte[] magic = new byte[4];
                    mFile.readFully(magic);
                    if (!java.util.Arrays.equals(magic, MAGIC)) {
                        // Not our file, recreate
                        mFile.setLength(0);
                        mFile.write(MAGIC);
                        mFile.writeInt(VERSION);
                        mFile.writeLong(HEADER_SIZE);
                        mWritePos = HEADER_SIZE;
                    } else {
                        int version = mFile.readInt();
                        if (version != VERSION) {
                            // Version mismatch, recreate
                            mFile.setLength(0);
                            mFile.seek(0);
                            mFile.write(MAGIC);
                            mFile.writeInt(VERSION);
                            mFile.writeLong(HEADER_SIZE);
                            mWritePos = HEADER_SIZE;
                        } else {
                            mWritePos = mFile.readLong();
                            if (mWritePos < HEADER_SIZE || mWritePos >= MAX_LOG_SIZE) {
                                mWritePos = HEADER_SIZE;
                            }
                        }
                    }
                }
                mEnabled = true;
                log("PROBE", "initialized", "file=" + logFile.getAbsolutePath(), "pos=" + mWritePos);
            } catch (IOException e) {
                Log.e(TAG, "Failed to init probe log", e);
                mEnabled = false;
            }
        }
    }

    /**
     * Log a probe event with key-value pairs.
     */
    public void log(String event, String... kvs) {
        if (!mEnabled) return;
        synchronized (mLock) {
            if (mFile == null) return;
            try {
                StringBuilder sb = new StringBuilder(256);
                sb.append('[').append(mTimeFormat.format(new Date())).append(']');
                sb.append(' ').append(event);
                for (String kv : kvs) {
                    sb.append(' ').append(kv);
                }
                sb.append('\n');

                byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
                long remaining = MAX_LOG_SIZE - mWritePos;
                if (data.length > remaining) {
                    // Wrap around
                    mWritePos = HEADER_SIZE;
                    mFile.seek(mWritePos);
                }

                mFile.write(data);
                mWritePos += data.length;
                // Update header position
                mFile.seek(MAGIC.length + 4);
                mFile.writeLong(mWritePos);
            } catch (IOException e) {
                // Silently fail - don't crash the app for diagnostics
                mEnabled = false;
            }
        }
    }

    /**
     * Log layout state from onGlobalLayout.
     */
    public void logLayout(int windowBottom, int viewBottom, int margin, int pxHidden,
                          boolean visible, boolean visibleByMargin, boolean visibleByExtra) {
        log("LAYOUT",
            "winBot=" + windowBottom,
            "viewBot=" + viewBottom,
            "margin=" + margin,
            "pxHidden=" + pxHidden,
            "vis=" + (visible ? 1 : 0),
            "visMargin=" + (visibleByMargin ? 1 : 0),
            "visExtra=" + (visibleByExtra ? 1 : 0));
    }

    /**
     * Log measure event.
     */
    public void logMeasure(int width, int height, Integer marginToSet) {
        log("MEASURE",
            "w=" + width,
            "h=" + height,
            "marginSet=" + (marginToSet != null ? marginToSet : "null"));
    }

    /**
     * Log window insets.
     */
    public void logInsets(int statusBarHeight, int imeBottom, int navBarBottom) {
        log("INSETS",
            "statusBar=" + statusBarHeight,
            "imeBot=" + imeBottom,
            "navBot=" + navBarBottom,
            "uptime=" + SystemClock.uptimeMillis());
    }

    /**
     * Log lifecycle event.
     */
    public void logLifecycle(String event, String... extra) {
        String[] all = new String[extra.length + 1];
        all[0] = "t=" + SystemClock.uptimeMillis();
        System.arraycopy(extra, 0, all, 1, extra.length);
        log("LIFE_" + event, all);
    }

    /**
     * Log keyboard state change.
     */
    public void logKeyboard(String action, int softInputMode, int imeHeight) {
        log("KEYBOARD",
            "act=" + action,
            "mode=0x" + Integer.toHexString(softInputMode),
            "imeH=" + imeHeight);
    }

    /**
     * Log margin change decision.
     */
    public void logMargin(String reason, int oldMargin, int newMargin, long timeSinceLast) {
        log("MARGIN",
            "reason=" + reason,
            "old=" + oldMargin,
            "new=" + newMargin,
            "dt=" + timeSinceLast);
    }

    public void shutdown() {
        synchronized (mLock) {
            if (mFile != null) {
                try {
                    mFile.close();
                } catch (IOException ignored) {}
                mFile = null;
            }
            mEnabled = false;
        }
    }

    public boolean isEnabled() {
        return mEnabled;
    }
}
