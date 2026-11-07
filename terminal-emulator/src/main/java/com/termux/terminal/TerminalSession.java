package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * A queue written to from the parser worker thread due to user interaction, and read by another thread which
     * forwards by writing to the {@link #mTerminalFileDescriptor}. Admission never waits for the PTY writer:
     * an over-capacity chunk is reported so the session can fail explicitly instead of truncating input.
     */
    final TerminalOutboundQueue mTerminalToProcessIOQueue = new TerminalOutboundQueue();
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    volatile TerminalSessionClient mClient;

    /** Callback passed to the emulator; posts callbacks to {@link #mClient} on the main thread. */
    private volatile TerminalSessionClient mEmulatorClient;

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
    private int mTerminalFileDescriptor = -1;

    /** Logical session failure is distinct from the actual waitpid status. */
    private volatile Throwable mFailureCause;
    private volatile Integer mProcessExitStatus;
    private final AtomicBoolean mFailureRequested = new AtomicBoolean();
    private final AtomicBoolean mResourcesClosed = new AtomicBoolean();
    private final AtomicBoolean mFailureCompleted = new AtomicBoolean();
    private final AtomicBoolean mFinishNotified = new AtomicBoolean();

    /** Set on the main thread when the post-exit reader grace period expires. */
    private volatile boolean mProcessReaderStopRequested;

    /** Java-side owners for the duplicated PTY descriptors. */
    private final Object mPtyStreamLock = new Object();
    // Audit L1-1: the dup'd descriptors must be closed explicitly exactly once;
    // no Java stream wrapper owns them.
    private FileDescriptor mPtyInputFd;
    private FileDescriptor mPtyOutputFd;
    private boolean mPtyStreamsCloseRequested;

    /** Exit was observed with WNOWAIT, before the PID can be reaped/reused. */
    private volatile boolean mProcessExited;
    /** The owned waiter completed the real reap; completion must wait for this fact. */
    private volatile boolean mProcessReaped;
    /** Unexpected wait failure revokes unsafe numeric-PID signalling without inventing exit. */
    private volatile boolean mProcessOwnershipLost;

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

        if (mParserWorker != null) mParserWorker.setClient(mEmulatorClient);
        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(mEmulatorClient);
    }

    /**
     * Set the sink that receives immutable terminal model frames from the parser worker.
     * The worker caches the latest frame for snapshot methods and forwards it to the view sink.
     */
    public synchronized void setFrameSink(TerminalFrameSink sink) {
        final TerminalFrameSink delegate = sink;
        mFrameSink = new TerminalFrameSink() {
            @Override
            public void publishFrame(TerminalModelFrame frame) {
                synchronized (TerminalSession.this) {
                    // A worker can retain the previous route across detach/reattach.
                    // Only the current route may update the snapshot cache.
                    if (mFrameSink != this) return;
                    mLatestFrame = frame;
                }
                if (delegate != null) delegate.publishFrame(frame);
            }

            @Override
            public boolean shouldCaptureSnapshot() {
                return delegate == null || delegate.shouldCaptureSnapshot();
            }

            @Override
            public void onFrameConsumed(TerminalModelFrame frame) {
                if (delegate != null) delegate.onFrameConsumed(frame);
            }
        };
        if (mParserWorker != null) mParserWorker.setFrameSink(mFrameSink);
    }

    /**
     * Notify the parser worker that a previously published frame has been consumed by
     * the renderer. This allows the worker to republish if it skipped snapshots while
     * a frame was pending.
     */
    public void onFrameConsumed(TerminalModelFrame frame) {
        TerminalParserWorker worker = mParserWorker;
        if (worker != null) worker.onFrameConsumed(frame);
    }

    /** Detach the current view route; future worker frames are not delivered to it.
     *
     * <p>The detached sink refuses eager snapshot capture: with no consumer, the worker
     * would otherwise pay a full screen copy per PTY publish batch and drop the frame
     * into {@code mLatestFrame} where nobody reads it. Snapshot getters fall back to
     * synchronized emulator reads while detached, and a re-attach resumes capture through
     * the new view sink (its mailbox is empty, so {@code shouldCaptureSnapshot()} is
     * true on the next publish and the pending dirty state produces a fresh frame). */
    public synchronized void detachFrameSink() {
        // No further snapshots are captured while detached; cached attached state
        // must not win over the live emulator in background-session getters.
        mLatestFrame = null;
        mFrameSink = new TerminalFrameSink() {
            @Override
            public void publishFrame(TerminalModelFrame frame) {
                // The forced FINISH snapshot has no view consumer. Leave getters
                // on the current emulator rather than reviving an attached cache.
            }

            @Override
            public boolean shouldCaptureSnapshot() {
                return false;
            }
        };
        if (mParserWorker != null) mParserWorker.setFrameSink(mFrameSink);
    }

    /** Return a point-in-time snapshot of parser-side pipeline counters. */
    public TerminalParserMetrics.Snapshot getParserMetricsSnapshot() {
        TerminalParserWorker worker = mParserWorker;
        return worker == null ? new TerminalParserMetrics().snapshot() : worker.getMetricsSnapshot();
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

    /** Last viewport top row handed to the parser worker, for main-thread dedupe. */
    private int mLastViewportTopRow = Integer.MIN_VALUE;

    /** Request a new immutable model frame for a transcript viewport change. */
    public void setViewport(int topRow) {
        if (mParserWorker != null) {
            // The view calls setViewport() on every onScreenUpdated() notification.
            // Without dedupe, an idle app re-enqueues a viewport command per
            // published frame, which publishes another frame, which notifies the
            // view again: an exponential publish loop. Skipping an unchanged
            // request is safe because the worker already holds that viewport and
            // the view invalidates itself when it actually changes mTopRow.
            if (topRow == mLastViewportTopRow) return;
            mLastViewportTopRow = topRow;
            mParserWorker.requestViewport(topRow);
        }
    }

    /** Whether the terminal emulator has been initialized by {@link #updateSize(int, int, int, int)}. */
    public boolean isEmulatorInitialized() {
        return mEmulator != null;
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
        mProcessExited = false;
        synchronized (mPtyStreamLock) {
            mPtyStreamsCloseRequested = false;
        }
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mEmulatorClient);
        mParserWorker = new TerminalParserWorker(mEmulator, mProcessToTerminalIOQueue, mFrameSink, mEmulatorClient, this, 64 * 1024, 32 * 1024);
        mParserWorker.start();

        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];
        mClient.setTerminalShellPid(this, mShellPid);

        // Keep the subprocess' original master FD owned by this session for
        // ioctl() and the single final JNI.close().  Each Java stream gets its
        // own duplicated descriptor: FileInputStream/FileOutputStream both
        // close their descriptor, so sharing the raw FD would create multiple
        // close owners and make a late close vulnerable to fd-number reuse.
        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);
        FileDescriptor inputFileDescriptor = null;
        FileDescriptor outputFileDescriptor = null;
        try {
            inputFileDescriptor = Os.dup(terminalFileDescriptorWrapped);
            outputFileDescriptor = Os.dup(terminalFileDescriptorWrapped);
        } catch (ErrnoException e) {
            // If the second dup fails, the first duplicate is already a Java
            // stream candidate and must be closed before releasing the raw
            // master FD. Otherwise initialization failure leaks one PTY fd.
            if (inputFileDescriptor != null) {
                try {
                    Os.close(inputFileDescriptor);
                } catch (ErrnoException ignored) {
                    // Best effort while unwinding initialization.
                }
            }
            if (outputFileDescriptor != null) {
                try {
                    Os.close(outputFileDescriptor);
                } catch (ErrnoException ignored) {
                    // Best effort while unwinding initialization.
                }
            }
            JNI.close(mTerminalFileDescriptor);
            throw new IllegalStateException("Failed to duplicate terminal pty descriptor", e);
        }
        final FileDescriptor inputDescriptor = inputFileDescriptor;
        final FileDescriptor outputDescriptor = outputFileDescriptor;
        try {
            // Both worker loops below use nonblocking I/O with bounded poll() waits
            // and re-check the close flag: a blocking write()/read() on a full PTY
            // master cannot be interrupted by close() (the in-flight syscall
            // survives the fd close), which used to leave TermSessionOutputWriter
            // stuck in FileOutputStream.write after closePtyStreams().
            // O_NONBLOCK is a file-status flag shared by the whole open file
            // description (raw master included); the raw master is only used for
            // TIOCSWINSZ and the final JNI.close(), which do not block.
            Os.fcntlInt(inputDescriptor, OsConstants.F_SETFL,
                Os.fcntlInt(inputDescriptor, OsConstants.F_GETFL, 0) | OsConstants.O_NONBLOCK);
            Os.fcntlInt(outputDescriptor, OsConstants.F_SETFL,
                Os.fcntlInt(outputDescriptor, OsConstants.F_GETFL, 0) | OsConstants.O_NONBLOCK);
        } catch (ErrnoException e) {
            try {
                Os.close(inputDescriptor);
            } catch (ErrnoException ignored) {
                // Best effort while unwinding initialization.
            }
            try {
                Os.close(outputDescriptor);
            } catch (ErrnoException ignored) {
                // Best effort while unwinding initialization.
            }
            JNI.close(mTerminalFileDescriptor);
            throw new IllegalStateException("Failed to configure nonblocking terminal pty descriptors", e);
        }
        synchronized (mPtyStreamLock) {
            mPtyInputFd = inputDescriptor;
            mPtyOutputFd = outputDescriptor;
        }

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        if (mProcessReaderStopRequested) return;
                        int read = readPtyNonblocking(inputDescriptor, buffer);
                        if (read <= 0) return;
                        if (mProcessReaderStopRequested) return;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mParserWorker.requestAppend();
                    }
                } finally {
                    mMainThreadHandler.sendEmptyMessage(MSG_PROCESS_READER_FINISHED);
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        if (!writePtyNonblocking(outputDescriptor, buffer, bytesToWrite)) return;
                    }
                } catch (RuntimeException writeError) {
                    // A PTY write failure is a transport failure while the session
                    // still owns a live child. A close driven by process exit or an
                    // already-recorded failure must not be misreported as a new one.
                    if (!mProcessExited && !mFailureRequested.get()) {
                        onParserWorkerFailure(writeError);
                    }
                }
            }
        }.start();

        final int watchedPid = mShellPid;
        new Thread("TermSessionWaiter[pid=" + watchedPid + "]") {
            @Override
            public void run() {
                try {
                    int observedExit = JNI.waitForExit(watchedPid);
                    synchronized (TerminalSession.this) {
                        // WNOWAIT still reserves the PID here. This lock also covers
                        // the signal syscall, so no admitted kill can outlive revocation.
                        mProcessExitStatus = observedExit;
                        mProcessExited = true;
                    }
                    int processExitCode = JNI.waitFor(watchedPid);
                    synchronized (TerminalSession.this) {
                        mProcessExitStatus = processExitCode;
                        mProcessReaped = true;
                    }
                    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
                } catch (Throwable waitError) {
                    TerminalFailureUtils.rethrowFatal(waitError);
                    synchronized (TerminalSession.this) { mProcessOwnershipLost = true; }
                    TerminalFailureUtils.log(mClient, LOG_TAG, "Lost child wait ownership", waitError);
                    // Do not signal a numeric PID after losing its wait/ownership proof.
                    onParserWorkerFailure(waitError);
                    completeFailedSession();
                }
            }
        }.start();

    }

    /**
     * Write data to the shell process.
     *
     * <p>Caller-originated input (UI keys, pastes submitted from another thread)
     * is copied and queued as a command on the parser worker, so it is ordered
     * with paste/mouse commands and parsed on the worker thread. The parser
     * worker's own writes (paste chunks, protocol replies) are admitted straight
     * to the bounded outbound queue at that exact command position. Neither path
     * waits for the PTY writer; overload becomes an explicit session failure.</p>
     */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (count == 0) return;
        if (offset < 0 || count < 0 || count > data.length - offset) {
            throw new IllegalArgumentException("offset/count outside buffer");
        }

        TerminalParserWorker worker = mParserWorker;
        if (worker != null && worker.isWorkerThread()) {
            if (mFailureRequested.get() || mProcessExited) return;
            // offer() copies synchronously before returning, so the parser-owned
            // buffer is safe to hand over without an extra copy here.
            TerminalOutboundQueue.OfferResult offered = mTerminalToProcessIOQueue.offer(data, offset, count);
            if (offered != TerminalOutboundQueue.OfferResult.ACCEPTED)
                failInputOverflow("parser output queue (" + offered + ")", count);
            return;
        }

        if (!isRunning() || mFailureRequested.get() || mProcessExited) return;
        if (worker == null) return;
        TerminalParserWorker.InputAdmission admission = worker.requestWrite(data, offset, count);
        if (admission != TerminalParserWorker.InputAdmission.ACCEPTED)
            failInputOverflow("pending input admission (" + admission + ")", count);
    }

    /** Input that cannot be admitted within finite memory fails the transport, never truncates silently. */
    private void failInputOverflow(String edge, int bytes) {
        if (mFailureRequested.get() || mProcessExited) return;
        IOException error = new IOException("Terminal " + edge + " rejected " + bytes + " input bytes");
        TerminalParserWorker worker = mParserWorker;
        // Worker output happens inside the model monitor. Unwind to run() before
        // teardown, signalling or callbacks; only non-worker admission fails here.
        if (worker != null && worker.isWorkerThread()) throw new IllegalStateException(error);
        onParserWorkerFailure(error);
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

    /** Toggle whether auto-scroll is disabled, serialized with parser worker updates. */
    public void requestToggleAutoScrollDisabled() {
        if (mParserWorker != null) {
            mParserWorker.requestToggleAutoScrollDisabled();
        } else if (mEmulator != null) {
            synchronized (mEmulator) {
                mEmulator.toggleAutoScrollDisabled();
            }
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
        if (mEmulator == null) return 0;
        synchronized (mEmulator) {
            return mEmulator.getScrollCounter();
        }
    }

    /** @return cumulative scroll rows from the latest frame or live emulator. */
    public long getCumulativeScrollRows() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) return frame.cumulativeScrollRows;
        if (mEmulator == null) return 0;
        synchronized (mEmulator) {
            return mEmulator.getCumulativeScrollRows();
        }
    }

    /** Clear the scroll counter, serialized with parser worker updates. */
    public void clearScrollCounter() {
        if (mParserWorker != null) {
            // The view calls clearScrollCounter() on every onScreenUpdated()
            // notification; when nothing scrolled the reset is a semantic no-op.
            // Skipping it also breaks the publish -> notify -> clear -> publish
            // feedback loop for idle sessions.
            if (getScrollCounter() == 0) return;
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
        if (mEmulator == null) return "";
        synchronized (mEmulator) {
            return mEmulator.getScreen().getTranscriptText();
        }
    }

    /** @return whether the cursor is enabled in the latest frame or live emulator. */
    public boolean isCursorEnabled() {
        TerminalModelFrame frame = mLatestFrame;
        if (frame != null) {
            return frame.cursorEnabled;
        }
        if (mEmulator == null) return false;
        synchronized (mEmulator) {
            return mEmulator.isCursorEnabled();
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
        if (mEmulator == null) return null;
        synchronized (mEmulator) {
            return mEmulator.getScreen().getWordAtLocation(x, y);
        }
    }

    /** Get transcript text from the terminal session. */
    public String getTranscriptText(boolean linesJoined, boolean trim) {
        if (mEmulator == null) return null;
        synchronized (mEmulator) {
            TerminalBuffer buffer = mEmulator.getScreen();
            String text = linesJoined ? buffer.getTranscriptTextWithFullLinesJoined() : buffer.getTranscriptTextWithoutJoinedLines();
            if (text == null) return null;
            return trim ? text.trim() : text;
        }
    }

    /** Get selected text in the given region from the latest frame or live screen. */
    public String getSelectedText(int x1, int y1, int x2, int y2, boolean rectangular) {
        if (mEmulator == null) return null;
        synchronized (mEmulator) {
            return mEmulator.getScreen().getSelectedText(x1, y1, x2, y2, rectangular);
        }
    }

    /** Paste text into the terminal, serialized with parser worker updates. */
    public void paste(String text) {
        if (!isRunning() || mFailureRequested.get() || mProcessExited) return;
        TerminalParserWorker worker = mParserWorker;
        if (worker != null) {
            TerminalParserWorker.InputAdmission admission = worker.requestPaste(text);
            if (admission != TerminalParserWorker.InputAdmission.ACCEPTED) {
                failInputOverflow("paste admission (" + admission + ")",
                    text == null ? 0 : (int) Math.min((long) text.length() * 3 + 16, Integer.MAX_VALUE));
            }
        } else if (mEmulator != null) {
            synchronized (mEmulator) {
                mEmulator.paste(text);
            }
        }
    }

    /** Send a mouse event, serialized with parser worker updates. */
    public void sendMouseEvent(int button, int x, int y, boolean pressed) {
        if (!isRunning() || mFailureRequested.get() || mProcessExited) return;
        TerminalParserWorker worker = mParserWorker;
        if (worker != null) {
            TerminalParserWorker.InputAdmission admission = worker.requestSendMouseEvent(button, x, y, pressed);
            if (admission != TerminalParserWorker.InputAdmission.ACCEPTED) {
                failInputOverflow("mouse admission (" + admission + ")", 32);
            }
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
        ErrnoException signalError = null;
        synchronized (this) {
            if (mShellPid <= 0 || mProcessExited || mProcessOwnershipLost) return;
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException error) {
                signalError = error;
            }
        }
        if (signalError != null)
            TerminalFailureUtils.log(mClient, LOG_TAG, "Failed sending SIGKILL", signalError);
    }

    /** Cleanup resources after process exit (or failure before a process was started). */
    void cleanupResources(Integer exitStatus) {
        final int descriptor;
        synchronized (this) {
            if (!mResourcesClosed.compareAndSet(false, true)) return;
            mShellPid = -1;
            // A missing status means no wait result, not a fabricated child exit.
            if (exitStatus != null) mShellExitStatus = exitStatus;
            descriptor = mTerminalFileDescriptor;
            mTerminalFileDescriptor = -1;
        }
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        try {
            closePtyStreams();
        } finally {
            // An unstarted session owns no raw FD; never close stdin (fd 0).
            if (descriptor >= 0) JNI.close(descriptor);
        }
    }

    /** The cause of a logical parser/transport failure, not a fabricated child exit code. */
    public Throwable getFailureCause() { return mFailureCause; }

    /** Actual waitpid result, or null if no subprocess exit has been observed. */
    public Integer getProcessExitStatus() { return mProcessExitStatus; }

    /** Called outside the emulator monitor when the parser cannot continue safely. */
    void onParserWorkerFailure(Throwable cause) {
        TerminalFailureUtils.rethrowFatal(cause);
        synchronized (this) {
            if (mFailureRequested.get()) return;
            // Publish the cause before the volatile terminal-failure flag: the
            // main-thread exit/reader handler must never complete a cause-less failure.
            mFailureCause = cause;
            mFailureRequested.set(true);
        }
        mProcessReaderStopRequested = true;
        // Break both blocking queue edges before waiting for any process/reader event.
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        TerminalParserWorker worker = mParserWorker;
        if (worker != null) worker.abort();
        TerminalFailureUtils.log(mClient, LOG_TAG, "Terminal session failed", cause);

        // A real child is still owned until the waiter revokes kill authority.
        // Its exit message finishes failure independently of the now-dead parser.
        if (getPid() > 0 && !mProcessExited && !mProcessOwnershipLost) finishIfRunning();
        completeFailedSession();
    }

    private void completeFailedSession() {
        if (getPid() > 0 && !mProcessReaped && !mProcessOwnershipLost) return;
        mMainThreadHandler.removeMessages(MSG_PROCESS_READER_TIMEOUT);
        if (!mFailureCompleted.compareAndSet(false, true)) return;
        cleanupResources(mProcessExitStatus);
        // Best effort visual evidence is separate from mandatory resource termination.
        try {
            TerminalEmulator emulator = mEmulator;
            if (emulator != null) {
                TerminalModelFrame frame;
                synchronized (emulator) {
                    byte[] banner = "\u0018\r\n[Session failed - press Enter]".getBytes(StandardCharsets.UTF_8);
                    emulator.append(banner, banner.length);
                    frame = new TerminalModelFrame(emulator, 0, null, 0);
                    mLatestFrame = frame;
                }
                TerminalFrameSink sink = mFrameSink;
                if (sink != null) sink.publishFrame(frame);
            }
        } catch (Throwable visualError) {
            TerminalFailureUtils.rethrowFatal(visualError);
            TerminalFailureUtils.log(mClient, LOG_TAG, "Unable to publish failed-session frame", visualError);
        }
        // A broken frame sink must not suppress the independent state notification.
        if (mEmulator != null) {
            try {
                mEmulatorClient.onTextChanged(this);
            } catch (Throwable notificationError) {
                TerminalFailureUtils.rethrowFatal(notificationError);
                TerminalFailureUtils.log(mClient, LOG_TAG, "Failed-session state notification failed", notificationError);
            }
        }
        notifySessionFinishedOnce();
    }

    /** At-most-once completion attempt, after state/resources have reached terminal state. */
    void notifySessionFinishedOnce() {
        if (!mFinishNotified.compareAndSet(false, true)) return;
        try {
            mEmulatorClient.onSessionFinished(this);
        } catch (Throwable callbackError) {
            TerminalFailureUtils.rethrowFatal(callbackError);
            TerminalFailureUtils.log(mClient, LOG_TAG, "Session completion callback failed", callbackError);
        }
    }

    /**
     * Bounded nonblocking PTY read for the reader thread. Returns bytes read, or -1
     * when the reader must stop (close requested, EOF, hangup, or descriptor error).
     * The actual Os.read/poll calls run inside mPtyStreamLock so closePtyStreams()
     * can never close the descriptor while a syscall is in flight on it; with
     * O_NONBLOCK the lock hold time is bounded by the 50 ms poll timeout.
     */
    private int readPtyNonblocking(FileDescriptor fd, byte[] buffer) {
        while (true) {
            synchronized (mPtyStreamLock) {
                if (mPtyStreamsCloseRequested) return -1;
                try {
                    int n = Os.read(fd, buffer, 0, buffer.length);
                    if (n > 0) return n;
                    return -1; // n == 0: EOF
                } catch (InterruptedIOException e) {
                    continue; // interrupted: retry like EINTR
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) continue;
                    if (e.errno != OsConstants.EAGAIN) return -1; // EIO on child exit, EBADF on close
                }
                if (mProcessReaderStopRequested) return -1;
                if (!pollPtyLocked(fd, (short) OsConstants.POLLIN)) return -1;
            }
        }
    }

    /**
     * Nonblocking PTY write for the writer thread. Returns false when the writer
     * must stop quietly (close requested or the descriptor is gone); a real
     * transport error (e.g. EIO after the slave side is gone) is thrown so the
     * caller can apply the existing failure guards, matching the previous
     * FileOutputStream.write error reporting.
     */
    private boolean writePtyNonblocking(FileDescriptor fd, byte[] buffer, int length) {
        int offset = 0;
        while (offset < length) {
            synchronized (mPtyStreamLock) {
                if (mPtyStreamsCloseRequested) return false;
                try {
                    int n = Os.write(fd, buffer, offset, length - offset);
                    if (n > 0) {
                        offset += n;
                        continue;
                    }
                    // n == 0: no progress, wait for writability below.
                } catch (InterruptedIOException e) {
                    continue; // interrupted: retry like EINTR
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) continue;
                    if (e.errno == OsConstants.EBADF) return false; // our own close won the race
                    if (e.errno != OsConstants.EAGAIN) throw new RuntimeException("PTY write failed", e);
                }
                if (!pollPtyLocked(fd, (short) OsConstants.POLLOUT)) return false;
            }
        }
        return true;
    }

    /**
     * Bounded poll on a PTY descriptor while holding mPtyStreamLock. Returns false
     * only when the descriptor is gone (POLLNVAL/EBADF); hangup and error events
     * return true so the next nonblocking read/write observes EIO through the
     * syscall itself and keeps the previous error-reporting semantics. The 50 ms
     * timeout bounds both the lock hold and the worst-case close-notification lag.
     */
    private boolean pollPtyLocked(FileDescriptor fd, short events) {
        StructPollfd pollFd = new StructPollfd();
        pollFd.fd = fd;
        pollFd.events = events;
        final int rc;
        try {
            rc = Os.poll(new StructPollfd[]{pollFd}, 50);
        } catch (ErrnoException e) {
            return e.errno == OsConstants.EINTR;
        }
        if (rc <= 0) return true;
        return (pollFd.revents & OsConstants.POLLNVAL) == 0;
    }

    /** Close Java-owned duplicate descriptors; the worker loops notice the flag. */
    private void closePtyStreams() {
        final FileDescriptor inFd;
        final FileDescriptor outFd;
        synchronized (mPtyStreamLock) {
            mPtyStreamsCloseRequested = true;
            inFd = mPtyInputFd;
            outFd = mPtyOutputFd;
            mPtyInputFd = null;
            mPtyOutputFd = null;
        }
        // Audit L1-1: close the dup'd descriptors explicitly and exactly once.
        // Errors are ignored: cleanupResources() still JNI.close()es the master.
        if (inFd != null) {
            try {
                Os.close(inFd);
            } catch (ErrnoException ignored) {
                // EBADF or already closed: the master JNI.close still follows.
            }
        }
        if (outFd != null) {
            try {
                Os.close(outFd);
            } catch (ErrnoException ignored) {
                // Same as above.
            }
        }
    }


    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid > 0 && !mProcessExited;
    }

    /**
     * Actual shell exit status; valid when {@link #getProcessExitStatus()} is non-null.
     * A parser/transport failure is reported by {@link #getFailureCause()}, never by
     * replacing the child's status. Unstarted sessions retain the legacy default.
     */
    public synchronized int getExitStatus() {
        Integer observedStatus = mProcessExitStatus;
        return observedStatus != null ? observedStatus : mShellExitStatus;
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

    public synchronized int getPid() {
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
                mProcessExitStatus = exitCode;
                mProcessExited = true;
                mProcessReaped = true;
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
                closePtyStreams();
                mExitCoordinator.markReaderTimeout();
                Logger.logWarn(mClient, LOG_TAG, "event=PTY_READER_TIMEOUT session=" + mHandle);
            }

            if (mFailureRequested.get()) {
                // The parser is dead: never queue its normal FINISH command. Complete
                // only after a real exit fact, or when no subprocess was ever started.
                if (mProcessReaped || mProcessOwnershipLost || getPid() <= 0) completeFailedSession();
                return;
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
