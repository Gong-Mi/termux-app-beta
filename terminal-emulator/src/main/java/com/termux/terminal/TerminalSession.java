package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int, int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_PROCESS_EXITED = 4;
    private static final int MSG_PROCESS_READER_FINISHED = 5;
    private static final int MSG_PROCESS_READER_TIMEOUT = 6;

    private static final long PROCESS_READER_FINISH_GRACE_MILLIS = 2000;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** Callback passed to the emulator; posts callbacks to {@link #mClient} on the main thread. */
    private TerminalSessionClient mEmulatorClient;

    /** Sink that will receive immutable model frames once the parser worker is wired in. */
    private TerminalFrameSink mFrameSink;

    /** Parser worker owning {@link #mEmulator} mutation. Not started yet. */
    private TerminalParserWorker mParserWorker;

    /** Latest frame published by the parser worker, used by main-thread snapshot methods. */
    private volatile TerminalModelFrame mLatestFrame;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int, int, int)}.
     */
    private int mTerminalFileDescriptor;

    /** Set on the main thread when the post-exit reader grace period expires. */
    private volatile boolean mProcessReaderStopRequested;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
        this.mEmulatorClient = wrapClient(client);
    }

    private TerminalSessionClient wrapClient(TerminalSessionClient client) {
        return new TerminalSessionClientMainThreadWrapper(client, new Handler(Looper.getMainLooper()));
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        mEmulatorClient = wrapClient(client);

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(mEmulatorClient);
    }

    /**
     * Set the sink that receives immutable terminal model frames from the parser worker.
     * Currently a stub that caches frames for snapshot methods; the worker is not yet active.
     */
    public synchronized void setFrameSink(TerminalFrameSink sink) {
        final TerminalFrameSink delegate = sink;
        mFrameSink = new TerminalFrameSink() {
            @Override
            public void publishFrame(TerminalModelFrame frame) {
                mLatestFrame = frame;
                if (delegate != null) delegate.publishFrame(frame);
            }
        };
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
            mParserWorker.requestResize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mEmulatorClient);
        mParserWorker = new TerminalParserWorker(mEmulator, mProcessToTerminalIOQueue, mFrameSink, mEmulatorClient, this, 64 * 1024, 32 * 1024);
        mParserWorker.start();

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];
        mClient.setTerminalShellPid(this, mShellPid);

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        if (mProcessReaderStopRequested) return;
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (mProcessReaderStopRequested) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mParserWorker.requestAppend();
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                } finally {
                    mMainThreadHandler.sendEmptyMessage(MSG_PROCESS_READER_FINISHED);
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
            }
        }.start();

    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** @return whether mouse tracking is active, based on the latest frame or emulator. */
    public boolean isMouseTrackingActive() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.mouseTrackingActive;
        if (mEmulator == null) return false;
        synchronized (mEmulator) {
            return mEmulator.isMouseTrackingActive();
        }
    }

    /** @return whether the alternate buffer is active. */
    public boolean isAlternateBufferActive() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.alternateBufferActive;
        if (mEmulator == null) return false;
        synchronized (mEmulator) {
            return mEmulator.isAlternateBufferActive();
        }
    }

    /** @return whether auto-scroll is disabled. */
    public boolean isAutoScrollDisabled() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.autoScrollDisabled;
        if (mEmulator == null) return false;
        synchronized (mEmulator) {
            return mEmulator.isAutoScrollDisabled();
        }
    }

    /** @return number of visible screen rows. */
    public int getScreenRows() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.rows;
        if (mEmulator == null) return 0;
        synchronized (mEmulator) {
            return mEmulator.mRows;
        }
    }

    /** @return number of screen columns. */
    public int getScreenColumns() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.columns;
        if (mEmulator == null) return 0;
        synchronized (mEmulator) {
            return mEmulator.mColumns;
        }
    }

    /** @return cursor column, or 0 if unavailable. */
    public int getCursorCol() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.cursorCol;
        if (mEmulator == null) return 0;
        synchronized (mEmulator) {
            return mEmulator.getCursorCol();
        }
    }

    /** @return cursor row, or 0 if unavailable. */
    public int getCursorRow() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.cursorRow;
        if (mEmulator == null) return 0;
        synchronized (mEmulator) {
            return mEmulator.getCursorRow();
        }
    }

    /** @return active rows (transcript + screen) from the latest frame or live emulator. */
    public int getScreenActiveRows() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.activeTranscriptRows + frame.rows;
        synchronized (mEmulator) {
            return mEmulator != null ? mEmulator.getScreen().getActiveRows() : 0;
        }
    }

    /** @return current scroll counter from the latest frame or live emulator. */
    public int getScrollCounter() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.scrollCounter;
        synchronized (mEmulator) {
            return mEmulator != null ? mEmulator.getScrollCounter() : 0;
        }
    }

    /** Clear the scroll counter, serialized with parser worker updates. */
    public void clearScrollCounter() {
        if (mParserWorker != null) {
            mParserWorker.requestClearScrollCounter();
        } else if (mEmulator != null) {
            synchronized (mEmulator) {
                mEmulator.clearScrollCounter();
            }
        }
    }

    /** Set cursor blink state, serialized with parser worker updates. */
    public void setCursorBlinkState(boolean visible) {
        if (mParserWorker != null) {
            mParserWorker.requestSetCursorBlinkState(visible);
        } else if (mEmulator != null) {
            synchronized (mEmulator) {
                mEmulator.setCursorBlinkState(visible);
            }
        }
    }

    /** Enable/disable cursor blinking, serialized with parser worker updates. */
    public void setCursorBlinkingEnabled(boolean enabled) {
        if (mParserWorker != null) {
            mParserWorker.requestSetCursorBlinkingEnabled(enabled);
        } else if (mEmulator != null) {
            synchronized (mEmulator) {
                mEmulator.setCursorBlinkingEnabled(enabled);
            }
        }
    }

    /** @return a transcript string of the latest frame's visible screen, or empty if unavailable. */
    public CharSequence getScreenTranscriptText() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.screen.getTranscriptText();
        synchronized (mEmulator) {
            return mEmulator != null ? mEmulator.getScreen().getTranscriptText() : "";
        }
    }

    /** @return whether the cursor is enabled in the latest frame or live emulator. */
    public boolean isCursorEnabled() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) {
            // Cursor visible implies enabled; use cursorStyle as fallback if needed.
            return frame.cursorVisible || frame.cursorStyle != 0;
        }
        synchronized (mEmulator) {
            return mEmulator != null && mEmulator.isCursorEnabled();
        }
    }

    /** @return active transcript rows. */
    public int getActiveTranscriptRows() {
        TerminalModelFrame frame = mLatestFrame;
        return frame != null ? frame.activeTranscriptRows : (mEmulator != null ? mEmulator.getScreen().getActiveTranscriptRows() : 0);
    }

    /** @return cursor-keys application mode, or false if unavailable. */
    public boolean isCursorKeysApplicationMode() {
        TerminalModelFrame frame = mLatestFrame;
        return frame != null ? frame.cursorKeysApplicationMode : (mEmulator != null && mEmulator.isCursorKeysApplicationMode());
    }

    /** @return keypad application mode, or false if unavailable. */
    public boolean isKeypadApplicationMode() {
        TerminalModelFrame frame = mLatestFrame;
        return frame != null ? frame.keypadApplicationMode : (mEmulator != null && mEmulator.isKeypadApplicationMode());
    }

    /** @return a copy of the current color palette, or null if unavailable. */
    public int[] getCurrentColors() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.copyPalette();
        if (mEmulator != null) return Arrays.copyOf(mEmulator.mColors.mCurrentColors, mEmulator.mColors.mCurrentColors.length);
        return null;
    }

    /** @return the word at the given column/row, or null if unavailable. */
    public String getWordAtLocation(int x, int y) {
        synchronized (mEmulator) {
            return mEmulator != null ? mEmulator.getScreen().getWordAtLocation(x, y) : null;
        }
    }

    /** Get transcript text from the terminal session. */
    public String getTranscriptText(boolean linesJoined, boolean trim) {
        synchronized (mEmulator) {
            if (mEmulator == null) return null;
            TerminalBuffer buffer = mEmulator.getScreen();
            String text = linesJoined ? buffer.getTranscriptTextWithFullLinesJoined() : buffer.getTranscriptTextWithoutJoinedLines();
            if (text == null) return null;
            return trim ? text.trim() : text;
        }
    }

    /** Get selected text in the given region from the latest frame or live screen. */
    public String getSelectedText(int x1, int y1, int x2, int y2, boolean rectangular) {
        synchronized (mEmulator) {
            return mEmulator != null ? mEmulator.getScreen().getSelectedText(x1, y1, x2, y2, rectangular) : null;
        }
    }

    /** Paste text into the terminal, serialized with parser worker updates. */
    public void paste(String text) {
        if (mParserWorker != null) {
            mParserWorker.requestPaste(text);
        } else if (mEmulator != null) {
            synchronized (mEmulator) {
                mEmulator.paste(text);
            }
        }
    }

    /** Send a mouse event, serialized with parser worker updates. */
    public void sendMouseEvent(int button, int x, int y, boolean pressed) {
        if (mParserWorker != null) {
            mParserWorker.requestSendMouseEvent(button, x, y, pressed);
        } else if (mEmulator != null) {
            synchronized (mEmulator) {
                mEmulator.sendMouseEvent(button, x, y, pressed);
            }
        }
    }

    /** Reset the terminal color palette. */
    public void resetColors() {
        if (mParserWorker != null) {
            mParserWorker.requestResetColors();
        } else if (mEmulator != null) {
            synchronized (mEmulator) {
                mEmulator.mColors.reset();
            }
        }
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        if (mParserWorker != null) {
            mParserWorker.requestReset();
        } else if (mEmulator != null) {
            mEmulator.reset();
            notifyScreenUpdate();
        }
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Logger.logWarn(mClient, LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        JNI.close(mTerminalFileDescriptor);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(client, LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final TerminalSessionExitCoordinator mExitCoordinator = new TerminalSessionExitCoordinator();

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                boolean scheduleReaderTimeout = mExitCoordinator.markProcessExited(exitCode);
                Logger.logInfo(mClient, LOG_TAG, "event=PROCESS_EXITED session=" + mHandle + " exitStatus=" + exitCode);
                if (scheduleReaderTimeout) {
                    sendEmptyMessageDelayed(MSG_PROCESS_READER_TIMEOUT, PROCESS_READER_FINISH_GRACE_MILLIS);
                }
            } else if (msg.what == MSG_PROCESS_READER_FINISHED) {
                mExitCoordinator.markReaderFinished();
                removeMessages(MSG_PROCESS_READER_TIMEOUT);
                Logger.logInfo(mClient, LOG_TAG, "event=PTY_READER_FINISHED session=" + mHandle);
            } else if (msg.what == MSG_PROCESS_READER_TIMEOUT) {
                mProcessReaderStopRequested = true;
                mExitCoordinator.markReaderTimeout();
                Logger.logWarn(mClient, LOG_TAG, "event=PTY_READER_TIMEOUT session=" + mHandle);
            }

            if (mExitCoordinator.shouldFinish(false)) {
                mExitCoordinator.markFinished();
                removeMessages(MSG_PROCESS_READER_TIMEOUT);
                int exitCode = mExitCoordinator.getExitStatus();
                Logger.logInfo(mClient, LOG_TAG, "event=FINISHING session=" + mHandle + " exitStatus=" + exitCode);
                mParserWorker.requestFinish(exitCode);
            }
        }

    }

}
