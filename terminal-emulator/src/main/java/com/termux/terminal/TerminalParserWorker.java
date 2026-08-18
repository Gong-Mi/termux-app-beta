package com.termux.terminal;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

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
    private final TerminalFrameSink mFrameSink;
    private final TerminalSessionClient mClient;
    private final TerminalSession mSession;
    private final byte[] mReceiveBuffer;
    private final int mMaxBytesPerBatch;
    private final BlockingQueue<Command> mCommandQueue = new ArrayBlockingQueue<>(64);
    private final Thread mThread;

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
        mStopped = true;
        mCommandQueue.offer(Command.stop());
    }

    public void requestAppend() {
        mCommandQueue.offer(Command.append());
    }

    public void requestResize(int columns, int rows, int cellWidth, int cellHeight) {
        mCommandQueue.offer(Command.resize(columns, rows, cellWidth, cellHeight));
    }

    public void requestViewport(int topRow) {
        mCommandQueue.offer(Command.viewport(topRow));
    }

    public void requestReset() {
        mCommandQueue.offer(Command.reset());
    }

    public void requestPaste(String text) {
        mCommandQueue.offer(Command.paste(text));
    }

    public void requestSendMouseEvent(int button, int x, int y, boolean pressed) {
        mCommandQueue.offer(Command.sendMouseEvent(button, x, y, pressed));
    }

    public void requestClearScrollCounter() {
        mCommandQueue.offer(Command.clearScrollCounter());
    }

    public void requestSetCursorBlinkState(boolean visible) {
        mCommandQueue.offer(Command.setCursorBlinkState(visible));
    }

    public void requestSetCursorBlinkingEnabled(boolean enabled) {
        mCommandQueue.offer(Command.setCursorBlinkingEnabled(enabled));
    }

    public void requestResetColors() {
        mCommandQueue.offer(Command.resetColors());
    }

    public void requestFinish(int exitCode) {
        mCommandQueue.offer(Command.finish(exitCode));
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
                    processAppend();
                    break;
                case MSG_RESIZE:
                    mEmulator.resize(cmd.columns, cmd.rows, cmd.cellWidth, cmd.cellHeight);
                    publishFrame();
                    break;
                case MSG_VIEWPORT:
                    mViewport = new Viewport(cmd.topRow);
                    publishFrame();
                    break;
                case MSG_RESET:
                    mEmulator.reset();
                    publishFrame();
                    break;
                case MSG_PASTE:
                    mEmulator.paste(cmd.text);
                    break;
                case MSG_SEND_MOUSE_EVENT:
                    mEmulator.sendMouseEvent(cmd.mouseButton, cmd.mouseColumn, cmd.mouseRow, cmd.mousePressed);
                    break;
                case MSG_CLEAR_SCROLL_COUNTER:
                    mEmulator.clearScrollCounter();
                    break;
                case MSG_SET_CURSOR_BLINK_STATE:
                    mEmulator.setCursorBlinkState(cmd.enabled);
                    break;
                case MSG_SET_CURSOR_BLINKING_ENABLED:
                    mEmulator.setCursorBlinkingEnabled(cmd.enabled);
                    break;
                case MSG_RESET_COLORS:
                    mEmulator.mColors.reset();
                    break;
                case MSG_FINISH:
                    processFinish(cmd.exitCode);
                    break;
                case MSG_STOP:
                    mStopped = true;
                    break;
            }
        }
    }

    private void processAppend() {
        int budgetRemaining = mMaxBytesPerBatch;
        while (budgetRemaining > 0 && !mStopped) {
            int toRead = Math.min(budgetRemaining, mReceiveBuffer.length);
            int read = mInputQueue.read(mReceiveBuffer, 0, toRead, false);
            if (read <= 0) break;
            mEmulator.append(mReceiveBuffer, read);
            budgetRemaining -= read;
        }

        if (budgetRemaining <= 0 && !mStopped && mInputQueue.hasData()) {
            // More input pending after budget exhausted; schedule another append.
            mCommandQueue.offer(Command.append());
        }

        publishFrame();
    }

    private void processFinish(int exitCode) {
        // Drain any input still queued before closing the PTY file descriptor.
        int budgetRemaining = mMaxBytesPerBatch;
        while (budgetRemaining > 0 && !mStopped) {
            int toRead = Math.min(budgetRemaining, mReceiveBuffer.length);
            int read = mInputQueue.read(mReceiveBuffer, 0, toRead, false);
            if (read <= 0) break;
            mEmulator.append(mReceiveBuffer, read);
            budgetRemaining -= read;
        }

        mSession.cleanupResources(exitCode);

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
