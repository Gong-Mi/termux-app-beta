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
    private static final int MSG_STOP = 5;

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
            case MSG_STOP:
                mStopped = true;
                break;
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

    private void publishFrame() {
        TerminalBuffer screen = mEmulator.getScreen();
        int dirtyCount = screen.getDirtyMutationCount();
        long[] dirtyBits = dirtyCount == 0 ? null : screen.getAndClearDirtyRowBits();
        TerminalModelFrame frame = new TerminalModelFrame(mEmulator, mViewport.topRow, dirtyBits, dirtyCount);
        mFrameSink.publishFrame(frame);
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

        private Command(int type, int columns, int rows, int cellWidth, int cellHeight, int topRow) {
            this.type = type;
            this.columns = columns;
            this.rows = rows;
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.topRow = topRow;
        }

        static Command append() { return new Command(MSG_APPEND, 0, 0, 0, 0, 0); }
        static Command resize(int columns, int rows, int cellWidth, int cellHeight) {
            return new Command(MSG_RESIZE, columns, rows, cellWidth, cellHeight, 0);
        }
        static Command viewport(int topRow) { return new Command(MSG_VIEWPORT, 0, 0, 0, 0, topRow); }
        static Command reset() { return new Command(MSG_RESET, 0, 0, 0, 0, 0); }
        static Command stop() { return new Command(MSG_STOP, 0, 0, 0, 0, 0); }
    }
}
