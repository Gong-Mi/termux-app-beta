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
    private long mReadNanos;
    private long mAppendNanos;
    private long mSnapshotNanos;
    private long mPublishNanos;
    private long mStepUtf8ContinuationBytes;
    private long mStepEscapeBytes;
    private long mStepCsiBytes;
    private long mStepOscOrDcsBytes;
    private long mStepControlBytes;
    private long mStepCodePointCalls;
    private long mStepPlainEmitted;
    private long mStepSetCharCalls;
    private long mStepScrollOperations;
    private long mStepSgrSequences;

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

    public synchronized void recordPhaseNanos(long readNanos, long appendNanos,
                                               long snapshotNanos, long publishNanos) {
        if (readNanos > 0) mReadNanos += readNanos;
        if (appendNanos > 0) mAppendNanos += appendNanos;
        if (snapshotNanos > 0) mSnapshotNanos += snapshotNanos;
        if (publishNanos > 0) mPublishNanos += publishNanos;
    }

    public synchronized void recordAppendSteps(TerminalAppendStepMetrics.Snapshot step) {
        mStepUtf8ContinuationBytes += step.utf8ContinuationBytes;
        mStepEscapeBytes += step.escapeBytes;
        mStepCsiBytes += step.csiBytes;
        mStepOscOrDcsBytes += step.oscOrDcsBytes;
        mStepControlBytes += step.controlBytes;
        mStepCodePointCalls += step.codePointCalls;
        mStepPlainEmitted += step.plainEmitted;
        mStepSetCharCalls += step.setCharCalls;
        mStepScrollOperations += step.scrollOperations;
        mStepSgrSequences += step.sgrSequences;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mInputBytes, mAppendCommands, mControlCommands,
            mPublishedFrames, mFinishCommands, mStopCommands,
            mReadNanos, mAppendNanos, mSnapshotNanos, mPublishNanos,
            mStepUtf8ContinuationBytes, mStepEscapeBytes, mStepCsiBytes,
            mStepOscOrDcsBytes, mStepControlBytes, mStepCodePointCalls,
            mStepPlainEmitted, mStepSetCharCalls, mStepScrollOperations,
            mStepSgrSequences);
    }

    /** Immutable point-in-time parser pipeline counters. */
    public static final class Snapshot {
        public final long inputBytes;
        public final long appendCommands;
        public final long controlCommands;
        public final long publishedFrames;
        public final long finishCommands;
        public final long stopCommands;
        public final long readNanos;
        public final long appendNanos;
        public final long snapshotNanos;
        public final long publishNanos;
        public final long stepUtf8ContinuationBytes;
        public final long stepEscapeBytes;
        public final long stepCsiBytes;
        public final long stepOscOrDcsBytes;
        public final long stepControlBytes;
        public final long stepCodePointCalls;
        public final long stepPlainEmitted;
        public final long stepSetCharCalls;
        public final long stepScrollOperations;
        public final long stepSgrSequences;

        private Snapshot(long inputBytes, long appendCommands, long controlCommands,
                         long publishedFrames, long finishCommands, long stopCommands,
                         long readNanos, long appendNanos, long snapshotNanos, long publishNanos,
                         long stepUtf8ContinuationBytes, long stepEscapeBytes, long stepCsiBytes,
                         long stepOscOrDcsBytes, long stepControlBytes, long stepCodePointCalls,
                         long stepPlainEmitted, long stepSetCharCalls, long stepScrollOperations,
                         long stepSgrSequences) {
            this.inputBytes = inputBytes;
            this.appendCommands = appendCommands;
            this.controlCommands = controlCommands;
            this.publishedFrames = publishedFrames;
            this.finishCommands = finishCommands;
            this.stopCommands = stopCommands;
            this.readNanos = readNanos;
            this.appendNanos = appendNanos;
            this.snapshotNanos = snapshotNanos;
            this.publishNanos = publishNanos;
            this.stepUtf8ContinuationBytes = stepUtf8ContinuationBytes;
            this.stepEscapeBytes = stepEscapeBytes;
            this.stepCsiBytes = stepCsiBytes;
            this.stepOscOrDcsBytes = stepOscOrDcsBytes;
            this.stepControlBytes = stepControlBytes;
            this.stepCodePointCalls = stepCodePointCalls;
            this.stepPlainEmitted = stepPlainEmitted;
            this.stepSetCharCalls = stepSetCharCalls;
            this.stepScrollOperations = stepScrollOperations;
            this.stepSgrSequences = stepSgrSequences;
        }
    }
}