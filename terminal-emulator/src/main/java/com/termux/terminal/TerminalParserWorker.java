package com.termux.terminal;

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

    private final TerminalEmulator mEmulator;
    private final ByteQueue mInputQueue;
    private volatile TerminalFrameSink mFrameSink;
    private volatile TerminalSessionClient mClient;
    private final TerminalSession mSession;
    private final byte[] mReceiveBuffer;
    private final int mMaxBytesPerBatch;
    private final TerminalParserMetrics mMetrics = new TerminalParserMetrics();
    private final BlockingQueue<Command> mCommandQueue = new LinkedBlockingQueue<>();
    private final Thread mThread;
    private final AtomicBoolean mAppendScheduled = new AtomicBoolean(false);
    private final AtomicBoolean mStopRequested = new AtomicBoolean(false);

    private volatile Viewport mViewport = new Viewport(0);
    private volatile boolean mStopped;

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
        if (mStopRequested.compareAndSet(false, true)) {
            mCommandQueue.add(Command.stop());
        }
    }

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

    public void requestAppend() {
        // Collapse multiple append notifications into at most one queued command.
        if (!mStopRequested.get() && mAppendScheduled.compareAndSet(false, true)) {
            mCommandQueue.add(Command.append());
        }
    }

    public void requestResize(int columns, int rows, int cellWidth, int cellHeight) {
        enqueueControl(Command.resize(columns, rows, cellWidth, cellHeight));
    }

    public void requestViewport(int topRow) {
        enqueueControl(Command.viewport(topRow));
    }

    public void requestReset() {
        enqueueControl(Command.reset());
    }

    public void requestPaste(String text) {
        enqueueControl(Command.paste(text));
    }

    public void requestSendMouseEvent(int button, int x, int y, boolean pressed) {
        enqueueControl(Command.sendMouseEvent(button, x, y, pressed));
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

    public void requestFinish(int exitCode) {
        enqueueControl(Command.finish(exitCode));
    }

    private void enqueueControl(Command cmd) {
        if (mStopped || mStopRequested.get()) return;
        // Control commands must not be silently dropped; LinkedBlockingQueue is unbounded,
        // so add() throws on failure instead of returning false.
        mCommandQueue.add(cmd);
    }

    private void run() {
        while (!mStopped) {
            try {
                Command cmd = mCommandQueue.take();
                processCommand(cmd);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processCommand(Command cmd) {
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
                    mViewport = new Viewport(cmd.topRow);
                    publishFrame();
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
                case MSG_SEND_MOUSE_EVENT:
                    mMetrics.recordControlCommand();
                    mEmulator.sendMouseEvent(cmd.mouseButton, cmd.mouseColumn, cmd.mouseRow, cmd.mousePressed);
                    publishFrame();
                    break;
                case MSG_CLEAR_SCROLL_COUNTER:
                    mMetrics.recordControlCommand();
                    mEmulator.clearScrollCounter();
                    publishFrame();
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
            }
        }
    }

    private void processAppend() {
        try {
            int budgetRemaining = mMaxBytesPerBatch;
            while (budgetRemaining > 0 && !mStopped) {
                int toRead = Math.min(budgetRemaining, mReceiveBuffer.length);
                int read = mInputQueue.read(mReceiveBuffer, 0, toRead, false);
                if (read <= 0) break;
                mEmulator.append(mReceiveBuffer, read);
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
        }
    }

    private void scheduleAppendIfNeeded() {
        if (!mStopRequested.get() && mAppendScheduled.compareAndSet(false, true)) {
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
        publishFrame();
        mClient.onSessionFinished(mSession);
        mStopped = true;
    }

    private void publishFrame() {
        TerminalBuffer screen = mEmulator.getScreen();
        int dirtyCount = screen.getDirtyMutationCount();
        long[] dirtyBits = dirtyCount == 0 ? null : screen.getAndClearDirtyRowBits();
        TerminalModelFrame frame = new TerminalModelFrame(mEmulator, mViewport.topRow, dirtyBits, dirtyCount);
        mMetrics.recordPublishedFrame();
        if (mFrameSink != null) mFrameSink.publishFrame(frame);
        mClient.onTextChanged(mSession); // posted to main thread; triggers UI invalidate
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
        final int mouseButton, mouseColumn, mouseRow;
        final boolean mousePressed;
        final boolean enabled;

        private Command(int type, int columns, int rows, int cellWidth, int cellHeight, int topRow, int exitCode,
                        String text, int mouseButton, int mouseColumn, int mouseRow, boolean mousePressed, boolean enabled) {
            this.type = type;
            this.columns = columns;
            this.rows = rows;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.topRow = topRow;
            this.exitCode = exitCode;
            this.text = text;
            this.mouseButton = mouseButton;
            this.mouseColumn = mouseColumn;
            this.mouseRow = mouseRow;
            this.mousePressed = mousePressed;
            this.enabled = enabled;
        }

        static Command append() { return new Command(MSG_APPEND, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, false, false); }
        static Command resize(int columns, int rows, int cellWidth, int cellHeight) {
            return new Command(MSG_RESIZE, columns, rows, cellWidth, cellHeight, 0, 0, null, 0, 0, 0, false, false);
        }
        static Command viewport(int topRow) { return new Command(MSG_VIEWPORT, 0, 0, 0, 0, topRow, 0, null, 0, 0, 0, false, false); }
        static Command reset() { return new Command(MSG_RESET, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, false, false); }
        static Command finish(int exitCode) { return new Command(MSG_FINISH, 0, 0, 0, 0, 0, exitCode, null, 0, 0, 0, false, false); }
        static Command stop() { return new Command(MSG_STOP, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, false, false); }
        static Command paste(String text) { return new Command(MSG_PASTE, 0, 0, 0, 0, 0, 0, text, 0, 0, 0, false, false); }
        static Command sendMouseEvent(int button, int x, int y, boolean pressed) {
            return new Command(MSG_SEND_MOUSE_EVENT, 0, 0, 0, 0, 0, 0, null, button, x, y, pressed, false);
        }
        static Command clearScrollCounter() { return new Command(MSG_CLEAR_SCROLL_COUNTER, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, false, false); }
        static Command setCursorBlinkState(boolean visible) { return new Command(MSG_SET_CURSOR_BLINK_STATE, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, false, visible); }
        static Command setCursorBlinkingEnabled(boolean enabled) { return new Command(MSG_SET_CURSOR_BLINKING_ENABLED, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, false, enabled); }
        static Command resetColors() { return new Command(MSG_RESET_COLORS, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, false, false); }
    }
}
