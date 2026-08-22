package com.termux.terminal;

/**
 * Thread-safe counters for the parser-worker side of the terminal pipeline.
 *
 * <p>This deliberately tracks transport/parser activity separately from
 * render-mailbox metrics. A published model frame is not a drawn frame, and a
 * mailbox-coalesced frame is not parser input loss.</p>
 */
public final class TerminalParserMetrics {

    private long mInputBytes;
    private long mAppendCommands;
    private long mControlCommands;
    private long mPublishedFrames;
    private long mFinishCommands;
    private long mStopCommands;

    public synchronized void recordAppendCommand() {
        mAppendCommands++;
    }

    public synchronized void recordInputBytes(long bytes) {
        if (bytes > 0) mInputBytes += bytes;
    }

    public synchronized void recordControlCommand() {
        mControlCommands++;
    }

    public synchronized void recordPublishedFrame() {
        mPublishedFrames++;
    }

    public synchronized void recordFinishCommand() {
        mFinishCommands++;
    }

    public synchronized void recordStopCommand() {
        mStopCommands++;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mInputBytes, mAppendCommands, mControlCommands,
            mPublishedFrames, mFinishCommands, mStopCommands);
    }

    /** Immutable point-in-time parser pipeline counters. */
    public static final class Snapshot {
        public final long inputBytes;
        public final long appendCommands;
        public final long controlCommands;
        public final long publishedFrames;
        public final long finishCommands;
        public final long stopCommands;

        private Snapshot(long inputBytes, long appendCommands, long controlCommands,
                         long publishedFrames, long finishCommands, long stopCommands) {
            this.inputBytes = inputBytes;
            this.appendCommands = appendCommands;
            this.controlCommands = controlCommands;
            this.publishedFrames = publishedFrames;
            this.finishCommands = finishCommands;
            this.stopCommands = stopCommands;
        }
    }
}
