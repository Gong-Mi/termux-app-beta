package com.termux.terminal;

import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background parser worker that owns {@link TerminalEmulator} mutation.
 *
 * <p>All screen state changes (input bytes, resize, reset) are serialized on
 * this worker thread. After each batch of input is parsed, the worker captures a
 * {@link TerminalModelFrame} and publishes it through the supplied
 * {@link TerminalFrameSink}. The sink is normally backed by a latest-only
 * mailbox in the view layer.</p>
 */
public final class TerminalParserWorker {

    private static final String LOG_TAG = "TerminalParserWorker";

    private static final int MSG_APPEND = 1;
    private static final int MSG_RESIZE = 2;
    private static final int MSG_VIEWPORT = 3;
    private static final int MSG_RESET = 4;
    private static final int MSG_FINISH = 5;
    private static final int MSG_STOP = 6;
    private static final int MSG_PASTE = 7;
    private static final int MSG_SEND_MOUSE_EVENT = 8;
    private static final int MSG_CLEAR_SCROLL_COUNTER = 9;
    private static final int MSG_SET_CURSOR_BLINK_STATE = 10;
    private static final int MSG_SET_CURSOR_BLINKING_ENABLED = 11;
    private static final int MSG_RESET_COLORS = 12;
    private static final int MSG_TOGGLE_AUTO_SCROLL_DISABLED = 13;
    private static final int MSG_REPUBLISH = 14;
    private static final int MSG_WRITE = 15;

    /**
     * Bounds for input that has been accepted from callers but not yet executed
     * by the parser. A conservative UTF-8 charge (3 bytes per UTF-16 unit plus
     * bracketed-paste markers) is reserved before a command is queued and
     * released when the command finishes, is aborted or is dropped on stop.
     */
    static final int MAX_PENDING_INPUT_BYTES = 2 * 1024 * 1024;
    static final int MAX_PENDING_INPUT_COMMANDS = 4096;

    /** Non-blocking admission result for caller-originated input commands. */
    public enum InputAdmission {
        /** The command was admitted and is now ordered on the parser queue. */
        ACCEPTED,
        /** The worker is stopped or stopping; the caller must treat the input as gone. */
        CLOSED,
        /** A pending byte/command bound would be exceeded; nothing was admitted. */
        OVERLOADED
    }

    private final TerminalEmulator mEmulator;
    private final ByteQueue mInputQueue;
    private volatile TerminalFrameSink mFrameSink;
    private volatile TerminalSessionClient mClient;
    private final TerminalSession mSession;
    private final byte[] mReceiveBuffer;
    private final int mMaxBytesPerBatch;
    private final TerminalParserMetrics mMetrics = new TerminalParserMetrics();
    private final BlockingQueue<Command> mCommandQueue = new LinkedBlockingQueue<>();
    /** Guards admission accounting together with stop/abort queue clearing. */
    private final Object mInputAdmissionLock = new Object();
    private int mPendingInputBytes;
    private int mPendingInputCommands;
    private final Thread mThread;
    private final AtomicBoolean mAppendScheduled = new AtomicBoolean(false);
    private final Object mViewportRequestLock = new Object();
    private boolean mViewportCommandScheduled;
    private int mPendingViewportTopRow;
    private final AtomicBoolean mStopRequested = new AtomicBoolean(false);
    private final AtomicBoolean mRepublishScheduled = new AtomicBoolean(false);

    private volatile Viewport mViewport = new Viewport(0);
    /** Parser-thread-only previous immutable screen for row sharing. */
    private TerminalScreenSnapshot mPreviousScreenSnapshot;
    private volatile boolean mStopped;
    /** Set when parser state changed but the sink asked us to skip snapshot. */
    private volatile boolean mDirty;
    /** Set by the consumer thread to indicate a previously published frame has been drawn,
     * allowing the worker to publish immediately even if the sink reports it cannot accept
     * (because the old frame is no longer blocking the slot). */
    private volatile boolean mConsumedPending;

    public TerminalParserWorker(TerminalEmulator emulator, ByteQueue inputQueue, TerminalFrameSink frameSink,
                                TerminalSessionClient client, TerminalSession session, int receiveBufferSize, int maxBytesPerBatch) {
        mEmulator = emulator;
        mInputQueue = inputQueue;
        mFrameSink = frameSink;
        mClient = client;
        mSession = session;
        mReceiveBuffer = new byte[receiveBufferSize];
        mMaxBytesPerBatch = maxBytesPerBatch;
        mThread = new Thread(this::run, "TermSessionParserWorker");
    }

    public void start() {
        mThread.start();
    }

    public void stop() {
        // Enqueue the sentinel before changing the loop state.  Setting mStopped first
        // would make enqueueControl() discard the only wake-up while the worker is
        // blocked in BlockingQueue.take().
        synchronized (mInputAdmissionLock) {
            if (!mStopRequested.compareAndSet(false, true)) return;
            mCommandQueue.add(Command.stop());
        }
    }

    /** Abort pending work after a terminal session failure, waking a parked worker. */
    void abort() {
        synchronized (mInputAdmissionLock) {
            mStopRequested.set(true);
            dropPendingCommandsLocked();
            mCommandQueue.add(Command.stop());
        }
    }

    /** True only on this worker; used to distinguish UI admission from model replies. */
    boolean isWorkerThread() { return Thread.currentThread() == mThread; }

    /** Test/lifecycle hook: wait until the worker has consumed the stop sentinel. */
    public boolean awaitStopped(long timeoutMs) throws InterruptedException {
        mThread.join(timeoutMs);
        return !mThread.isAlive();
    }

    public TerminalParserMetrics.Snapshot getMetricsSnapshot() {
        return mMetrics.snapshot();
    }

    /** Replace the frame route for a reattached/recreated view. */
    public void setFrameSink(TerminalFrameSink frameSink) {
        mFrameSink = frameSink;
    }

    /** Replace the callback route for a reattached/recreated session client. */
    public void setClient(TerminalSessionClient client) {
        mClient = client;
    }

    /**
     * Called from the consumer/render thread when a previously published frame has been drawn.
     * If the worker skipped snapshots while a frame was pending, this schedules a republish.
     */
    public void onFrameConsumed(TerminalModelFrame frame) {
        if (mStopped || mStopRequested.get()) return;
        mConsumedPending = true;
        if (mDirty && mRepublishScheduled.compareAndSet(false, true)) {
            mCommandQueue.add(Command.republish());
        }
    }

    public void requestAppend() {
        // Collapse multiple append notifications into at most one queued command.
        if (!mStopped && !mStopRequested.get() && mAppendScheduled.compareAndSet(false, true)) {
            mCommandQueue.add(Command.append());
        }
    }

    public void requestResize(int columns, int rows, int cellWidth, int cellHeight) {
        enqueueControl(Command.resize(columns, rows, cellWidth, cellHeight));
    }

    public void requestViewport(int topRow) {
        if (mStopped || mStopRequested.get()) return;
        synchronized (mViewportRequestLock) {
            mPendingViewportTopRow = topRow;
            if (mViewportCommandScheduled) return;
            mViewportCommandScheduled = true;
        }
        mCommandQueue.add(Command.viewport());
    }

    public void requestReset() {
        enqueueControl(Command.reset());
    }

    /**
     * Admit raw terminal input (a key, a key sequence or a mouse report) without
     * waiting. The bytes are copied before this method returns so callers may
     * reuse their encoding buffer, and the command is ordered on the same queue
     * as {@link #requestPaste(String)}. Overload is reported, never truncated.
     */
    public InputAdmission requestWrite(byte[] data, int offset, int count) {
        if (offset < 0 || count < 0 || count > data.length - offset) {
            throw new IllegalArgumentException("offset/count outside buffer");
        }
        if (count == 0) return mStopRequested.get() || mStopped ? InputAdmission.CLOSED : InputAdmission.ACCEPTED;
        synchronized (mInputAdmissionLock) {
            if (mStopped || mStopRequested.get()) return InputAdmission.CLOSED;
            if (count > MAX_PENDING_INPUT_BYTES - mPendingInputBytes ||
                mPendingInputCommands >= MAX_PENDING_INPUT_COMMANDS) return InputAdmission.OVERLOADED;
            // Check capacity before allocating, under the same gate as reservation.
            // Concurrent rejected callers must not each create a large temporary copy.
            byte[] copy = Arrays.copyOfRange(data, offset, offset + count);
            return enqueueInput(Command.write(copy, count));
        }
    }

    public InputAdmission requestPaste(String text) {
        int charCount = text == null ? 0 : text.length();
        long charge = (long) charCount * 3 + 16;
        int boundedCharge = (int) Math.min(charge, MAX_PENDING_INPUT_BYTES + 1L);
        return enqueueInput(Command.paste(text, boundedCharge));
    }

    public InputAdmission requestSendMouseEvent(int button, int x, int y, boolean pressed) {
        // Longest SGR report is well below this; a constant charge keeps the
        // admission check independent of the event's current model state.
        return enqueueInput(Command.sendMouseEvent(button, x, y, pressed));
    }

    public void requestClearScrollCounter() {
        enqueueControl(Command.clearScrollCounter());
    }

    public void requestSetCursorBlinkState(boolean visible) {
        enqueueControl(Command.setCursorBlinkState(visible));
    }

    public void requestSetCursorBlinkingEnabled(boolean enabled) {
        enqueueControl(Command.setCursorBlinkingEnabled(enabled));
    }

    public void requestResetColors() {
        enqueueControl(Command.resetColors());
    }

    public void requestToggleAutoScrollDisabled() {
        enqueueControl(Command.toggleAutoScrollDisabled());
    }

    public void requestFinish(int exitCode) {
        enqueueControl(Command.finish(exitCode));
    }

    private void enqueueControl(Command cmd) {
        if (mStopped || mStopRequested.get()) return;
        // Control commands must not be silently dropped; LinkedBlockingQueue is unbounded,
        // so add() throws on failure instead of returning false.
        mCommandQueue.add(cmd);
    }

    /**
     * Reserve the pending-input budget and queue an input-bearing command in one
     * critical section, so stop/abort can never clear the queue after a charge
     * was reserved but before its command became visible.
     */
    private InputAdmission enqueueInput(Command cmd) {
        synchronized (mInputAdmissionLock) {
            if (mStopped || mStopRequested.get()) return InputAdmission.CLOSED;
            if (cmd.charge > MAX_PENDING_INPUT_BYTES - mPendingInputBytes
                || mPendingInputCommands >= MAX_PENDING_INPUT_COMMANDS) {
                return InputAdmission.OVERLOADED;
            }
            mPendingInputBytes += cmd.charge;
            mPendingInputCommands++;
            mCommandQueue.add(cmd);
            return InputAdmission.ACCEPTED;
        }
    }

    /** Release the budget held by a finished or dropped input command. */
    private void releaseInputCharge(int charge) {
        if (charge <= 0) return;
        synchronized (mInputAdmissionLock) {
            releaseInputChargeLocked(charge);
        }
    }

    /** Called with {@link #mInputAdmissionLock} held: drop every queued command and its charge. */
    private void dropPendingCommandsLocked() {
        Command dropped;
        while ((dropped = mCommandQueue.poll()) != null) {
            releaseInputChargeLocked(dropped.charge);
        }
    }

    private void releaseInputChargeLocked(int charge) {
        if (charge <= 0) return;
        if (charge > mPendingInputBytes || mPendingInputCommands <= 0)
            throw new IllegalStateException("Input admission accounting underflow");
        mPendingInputBytes -= charge;
        mPendingInputCommands--;
    }

    /** Consistent admission diagnostics; never acquires the model or waits for PTY service. */
    public int getPendingInputBytes() {
        synchronized (mInputAdmissionLock) {
            return mPendingInputBytes;
        }
    }

    public int getPendingInputCommandCount() {
        synchronized (mInputAdmissionLock) {
            return mPendingInputCommands;
        }
    }

    private void run() {
        try {
            while (!mStopped) {
                try {
                    Command cmd = mCommandQueue.take();
                    processCommand(cmd);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    TerminalFailureUtils.rethrowFatal(t);
                    mStopped = true;
                    mStopRequested.set(true);
                    synchronized (mInputAdmissionLock) {
                        dropPendingCommandsLocked();
                    }
                    mInputQueue.close();
                    // Resource/process termination belongs to the session, not a fake
                    // completion callback from a worker whose state may be corrupted.
                    if (mSession != null) mSession.onParserWorkerFailure(t);
                    else TerminalFailureUtils.log(mClient, LOG_TAG, "Parser worker failed", t);
                }
            }
        } finally {
            // Commands enqueued after the stop sentinel (or after a failure) are dead
            // work: release their admission charges so the accounting ends at zero.
            synchronized (mInputAdmissionLock) {
                mStopRequested.set(true);
                dropPendingCommandsLocked();
            }
        }
    }

    private void processCommand(Command cmd) {
        try {
            synchronized (mEmulator) {
                switch (cmd.type) {
                    case MSG_APPEND:
                        mMetrics.recordAppendCommand();
                        processAppend();
                        break;
                    case MSG_RESIZE:
                        mMetrics.recordControlCommand();
                        mEmulator.resize(cmd.columns, cmd.rows, cmd.cellWidth, cmd.cellHeight);
                        publishFrame();
                        break;
                    case MSG_VIEWPORT:
                        mMetrics.recordControlCommand();
                        int requestedTopRow;
                        synchronized (mViewportRequestLock) {
                            requestedTopRow = mPendingViewportTopRow;
                            mViewportCommandScheduled = false;
                        }
                        int clampedTopRow = clampViewportTopRow(requestedTopRow);
                        if (clampedTopRow != mViewport.topRow) {
                            mViewport = new Viewport(clampedTopRow);
                            publishFrame();
                        }
                        break;
                    case MSG_RESET:
                        mMetrics.recordControlCommand();
                        mEmulator.reset();
                        publishFrame();
                        break;
                    case MSG_PASTE:
                        mMetrics.recordControlCommand();
                        mEmulator.paste(cmd.text);
                        publishFrame();
                        break;
                    case MSG_WRITE:
                        // Caller-originated raw input is executed at its queue position and
                        // forwarded straight to the transport. It does not publish a frame:
                        // input bytes alone do not mutate the screen model.
                        mEmulator.writeRawBytes(cmd.data, 0, cmd.data.length);
                        break;
                    case MSG_SEND_MOUSE_EVENT:
                        mMetrics.recordControlCommand();
                        mEmulator.sendMouseEvent(cmd.mouseButton, cmd.mouseColumn, cmd.mouseRow, cmd.mousePressed);
                        publishFrame();
                        break;
                    case MSG_CLEAR_SCROLL_COUNTER:
                        mMetrics.recordControlCommand();
                        if (mEmulator.getScrollCounter() != 0) {
                            mEmulator.clearScrollCounter();
                            publishFrame();
                        }
                        break;
                    case MSG_SET_CURSOR_BLINK_STATE:
                        mMetrics.recordControlCommand();
                        mEmulator.setCursorBlinkState(cmd.enabled);
                        publishFrame();
                        break;
                    case MSG_SET_CURSOR_BLINKING_ENABLED:
                        mMetrics.recordControlCommand();
                        mEmulator.setCursorBlinkingEnabled(cmd.enabled);
                        publishFrame();
                        break;
                    case MSG_RESET_COLORS:
                        mMetrics.recordControlCommand();
                        mEmulator.mColors.reset();
                        publishFrame();
                        break;
                    case MSG_TOGGLE_AUTO_SCROLL_DISABLED:
                        mMetrics.recordControlCommand();
                        mEmulator.toggleAutoScrollDisabled();
                        publishFrame();
                        break;
                    case MSG_FINISH:
                        mMetrics.recordControlCommand();
                        mMetrics.recordFinishCommand();
                        processFinish(cmd.exitCode);
                        break;
                    case MSG_STOP:
                        mMetrics.recordControlCommand();
                        mMetrics.recordStopCommand();
                        mStopped = true;
                        break;
                    case MSG_REPUBLISH:
                        mRepublishScheduled.set(false);
                        if (mDirty) {
                            mDirty = false;
                            publishFrame();
                        }
                        break;
                }
            }
        } finally {
            releaseInputCharge(cmd.charge);
        }
    }

    private void processAppend() {
        long readNanos = 0;
        long appendNanos = 0;
        try {
            int budgetRemaining = mMaxBytesPerBatch;
            while (budgetRemaining > 0 && !mStopped) {
                int toRead = Math.min(budgetRemaining, mReceiveBuffer.length);
                long readStart = System.nanoTime();
                int read = mInputQueue.read(mReceiveBuffer, 0, toRead, false);
                readNanos += System.nanoTime() - readStart;
                if (read <= 0) break;
                long appendStart = System.nanoTime();
                mEmulator.append(mReceiveBuffer, read);
                appendNanos += System.nanoTime() - appendStart;
                mMetrics.recordInputBytes(read);
                budgetRemaining -= read;
            }

            if (budgetRemaining <= 0 && !mStopped && mInputQueue.hasData()) {
                // More input pending after budget exhausted; schedule another append.
                scheduleAppendIfNeeded();
            }

            publishFrame();
        } finally {
            mAppendScheduled.set(false);
            // A concurrent requestAppend() may have been collapsed while we were processing.
            // If input still remains, ensure another append is scheduled.
            if (!mStopped && !mStopRequested.get() && mInputQueue.hasData()) {
                scheduleAppendIfNeeded();
            }
            mMetrics.recordPhaseNanos(readNanos, appendNanos, 0, 0);
            mMetrics.recordAppendSteps(mEmulator.getAndResetAppendStepDelta());
        }
    }

    private void scheduleAppendIfNeeded() {
        if (!mStopped && !mStopRequested.get() && mAppendScheduled.compareAndSet(false, true)) {
            mCommandQueue.add(Command.append());
        }
    }

    private void processFinish(int exitCode) {
        // Drain any input still queued before closing the PTY file descriptor.
        while (!mStopped) {
            int toRead = mReceiveBuffer.length;
            int read = mInputQueue.read(mReceiveBuffer, 0, toRead, false);
            if (read <= 0) break;
            mEmulator.append(mReceiveBuffer, read);
            mMetrics.recordInputBytes(read);
        }

        if (mSession != null) mSession.cleanupResources(exitCode);

        String exitDescription = "\r\n[Process completed";
        if (exitCode > 0) {
            exitDescription += " (code " + exitCode + ")";
        } else if (exitCode < 0) {
            exitDescription += " (signal " + (-exitCode) + ")";
        }
        exitDescription += " - press Enter]";

        byte[] bytesToWrite = exitDescription.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mEmulator.append(bytesToWrite, bytesToWrite.length);
        publishFrame(true);
        if (mSession != null) mSession.notifySessionFinishedOnce();
        else mClient.onSessionFinished(null);
        mStopped = true;
    }

    private void publishFrame() {
        publishFrame(false);
    }

    private void publishFrame(boolean forceTerminal) {
        TerminalFrameSink sink = mFrameSink;
        if (sink != null && !forceTerminal && !sink.shouldCaptureSnapshot()) {
            // The render side already has a frame that has not been consumed.
            // Skip the expensive snapshot unless the consumer has already drawn
            // the previous frame, in which case we can publish the latest state now.
            mDirty = true;
            if (!mConsumedPending) return;
        }

        int safeTopRow = clampViewportTopRow(mViewport.topRow);
        if (safeTopRow != mViewport.topRow) {
            mViewport = new Viewport(safeTopRow);
        }
        TerminalBuffer screen = mEmulator.getScreen();
        int dirtyCount = screen.getDirtyMutationCount();
        long[] dirtyBits = dirtyCount == 0 ? null : screen.getAndClearDirtyRowBits();
        long snapshotStart = System.nanoTime();
        TerminalModelFrame frame = new TerminalModelFrame(mEmulator, safeTopRow, dirtyBits, dirtyCount,
            mPreviousScreenSnapshot);
        long snapshotNanos = System.nanoTime() - snapshotStart;
        mPreviousScreenSnapshot = frame.screen;
        mMetrics.recordPublishedFrame();
        long publishStart = System.nanoTime();
        if (sink != null) sink.publishFrame(frame);
        mClient.onTextChanged(mSession); // posted to main thread; triggers UI invalidate
        mMetrics.recordPhaseNanos(0, 0, snapshotNanos, System.nanoTime() - publishStart);
        mDirty = false;
        mConsumedPending = false;
    }

    /**
     * Keep the worker-owned viewport in the emulator's current external row space.
     * Transcript length can shrink after a viewport command was queued, so this
     * check must also run immediately before every snapshot capture.
     */
    private int clampViewportTopRow(int requestedTopRow) {
        int activeTranscriptRows = mEmulator.getScreen().getActiveTranscriptRows();
        int minimumTopRow = -activeTranscriptRows;
        return Math.max(minimumTopRow, Math.min(0, requestedTopRow));
    }

    private static final class Viewport {
        final int topRow;
        Viewport(int topRow) { this.topRow = topRow; }
    }

    private static final class Command {
        final int type;
        final int columns, rows, cellWidth, cellHeight;
        final int topRow;
        final int exitCode;
        final String text;
        /** Immutable copy of caller input bytes for {@link #MSG_WRITE}. */
        final byte[] data;
        /** Pending-input budget held by this command, released when it finishes. */
        final int charge;
        final int mouseButton, mouseColumn, mouseRow;
        final boolean mousePressed;
        final boolean enabled;

        private Command(int type, int columns, int rows, int cellWidth, int cellHeight, int topRow, int exitCode,
                        String text, byte[] data, int charge, int mouseButton, int mouseColumn, int mouseRow,
                        boolean mousePressed, boolean enabled) {
            this.type = type;
            this.columns = columns;
            this.rows = rows;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.topRow = topRow;
            this.exitCode = exitCode;
            this.text = text;
            this.data = data;
            this.charge = charge;
            this.mouseButton = mouseButton;
            this.mouseColumn = mouseColumn;
            this.mouseRow = mouseRow;
            this.mousePressed = mousePressed;
            this.enabled = enabled;
        }

        static Command append() { return new Command(MSG_APPEND, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, false); }
        static Command resize(int columns, int rows, int cellWidth, int cellHeight) {
            return new Command(MSG_RESIZE, columns, rows, cellWidth, cellHeight, 0, 0, null, null, 0, 0, 0, 0, false, false);
        }
        static Command viewport() { return new Command(MSG_VIEWPORT, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, false); }
        static Command reset() { return new Command(MSG_RESET, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, false); }
        static Command finish(int exitCode) { return new Command(MSG_FINISH, 0, 0, 0, 0, 0, exitCode, null, null, 0, 0, 0, 0, false, false); }
        static Command stop() { return new Command(MSG_STOP, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, false); }
        static Command paste(String text, int charge) {
            return new Command(MSG_PASTE, 0, 0, 0, 0, 0, 0, text, null, charge, 0, 0, 0, false, false);
        }
        static Command write(byte[] data, int charge) {
            return new Command(MSG_WRITE, 0, 0, 0, 0, 0, 0, null, data, charge, 0, 0, 0, false, false);
        }
        static Command sendMouseEvent(int button, int x, int y, boolean pressed) {
            return new Command(MSG_SEND_MOUSE_EVENT, 0, 0, 0, 0, 0, 0, null, null, 32, button, x, y, pressed, false);
        }
        static Command clearScrollCounter() { return new Command(MSG_CLEAR_SCROLL_COUNTER, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, false); }
        static Command setCursorBlinkState(boolean visible) { return new Command(MSG_SET_CURSOR_BLINK_STATE, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, visible); }
        static Command setCursorBlinkingEnabled(boolean enabled) { return new Command(MSG_SET_CURSOR_BLINKING_ENABLED, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, enabled); }
        static Command resetColors() { return new Command(MSG_RESET_COLORS, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, false); }
        static Command toggleAutoScrollDisabled() { return new Command(MSG_TOGGLE_AUTO_SCROLL_DISABLED, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, false); }
        static Command republish() { return new Command(MSG_REPUBLISH, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, false, false); }
    }
}
