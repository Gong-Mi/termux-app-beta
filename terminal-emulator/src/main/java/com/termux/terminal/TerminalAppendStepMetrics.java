package com.termux.terminal;

/**
 * Per-append classification counters for the terminal parser pipeline.
 *
 * <p>These counters subdivide {@link TerminalEmulator#append(byte[], int)} so the
 * parser worker can attribute its CPU time to concrete steps: UTF-8 decoding,
 * escape/CSI/OSC state-machine bytes, plain printable emission, and the
 * screen mutation calls that actually write cells. The counters are only
 * touched by the parser worker thread; deltas are drained by the worker after
 * each append command.</p>
 */
public final class TerminalAppendStepMetrics {

    private long mInputBytes;
    private long mUtf8ContinuationBytes;
    private long mEscapeBytes;
    private long mCsiBytes;
    private long mOscOrDcsBytes;
    private long mControlBytes;
    private long mCodePointCalls;
    private long mPlainEmitted;
    private long mSetCharCalls;
    private long mScrollOperations;
    private long mSgrSequences;

    public void recordInputBytes(long bytes) {
        mInputBytes += bytes;
    }

    public void recordUtf8ContinuationByte() {
        mUtf8ContinuationBytes++;
    }

    public void recordEscapeByte() {
        mEscapeBytes++;
    }

    public void recordCsiByte() {
        mCsiBytes++;
    }

    public void recordOscOrDcsByte() {
        mOscOrDcsBytes++;
    }

    public void recordControlByte() {
        mControlBytes++;
    }

    public void recordCodePointCall() {
        mCodePointCalls++;
    }

    public void recordPlainEmitted() {
        mPlainEmitted++;
    }

    public void recordSetCharCall() {
        mSetCharCalls++;
    }

    public void recordScrollOperation() {
        mScrollOperations++;
    }

    public void recordSgrSequence() {
        mSgrSequences++;
    }

    /** Drain accumulated counters; returns a point-in-time snapshot. */
    public Snapshot getAndResetDelta() {
        Snapshot snapshot = new Snapshot(mInputBytes, mUtf8ContinuationBytes, mEscapeBytes,
            mCsiBytes, mOscOrDcsBytes, mControlBytes, mCodePointCalls, mPlainEmitted,
            mSetCharCalls, mScrollOperations, mSgrSequences);
        mInputBytes = 0;
        mUtf8ContinuationBytes = 0;
        mEscapeBytes = 0;
        mCsiBytes = 0;
        mOscOrDcsBytes = 0;
        mControlBytes = 0;
        mCodePointCalls = 0;
        mPlainEmitted = 0;
        mSetCharCalls = 0;
        mScrollOperations = 0;
        mSgrSequences = 0;
        return snapshot;
    }

    /** Immutable point-in-time classification of one or more append batches. */
    public static final class Snapshot {
        public final long inputBytes;
        public final long utf8ContinuationBytes;
        public final long escapeBytes;
        public final long csiBytes;
        public final long oscOrDcsBytes;
        public final long controlBytes;
        public final long codePointCalls;
        public final long plainEmitted;
        public final long setCharCalls;
        public final long scrollOperations;
        public final long sgrSequences;

        Snapshot(long inputBytes, long utf8ContinuationBytes, long escapeBytes,
                 long csiBytes, long oscOrDcsBytes, long controlBytes,
                 long codePointCalls, long plainEmitted, long setCharCalls,
                 long scrollOperations, long sgrSequences) {
            this.inputBytes = inputBytes;
            this.utf8ContinuationBytes = utf8ContinuationBytes;
            this.escapeBytes = escapeBytes;
            this.csiBytes = csiBytes;
            this.oscOrDcsBytes = oscOrDcsBytes;
            this.controlBytes = controlBytes;
            this.codePointCalls = codePointCalls;
            this.plainEmitted = plainEmitted;
            this.setCharCalls = setCharCalls;
            this.scrollOperations = scrollOperations;
            this.sgrSequences = sgrSequences;
        }
    }
}