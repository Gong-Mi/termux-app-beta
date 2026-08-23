package com.termux.view;

/**
 * Immutable-style accounting for the publish/draw/ack lifecycle of terminal render frames.
 *
 * <p>This class tracks the contract between the model (terminal emulator revisions),
 * the frame publisher ({@link TerminalView#onDraw}), and the renderer. It is intentionally
 * extracted from {@link TerminalView} so that the accounting can be unit-tested without
 * instantiating an Android View.
 */
public final class RenderFrameMetrics {

    private long mPublishedFrameCount;
    private long mLastPublishedScreenRevision = -1;
    private long mDrawnFrameCount;
    private long mLastDrawnScreenRevision = -1;
    private long mDroppedFrameCount;
    private long mCoalescedRevisionCount;
    private long mLastAckedScreenRevision = -1;

    /** Record that a frame carrying {@code screenRevision} has been handed to the renderer. */
    public synchronized void publish(long screenRevision) {
        mPublishedFrameCount++;
        mLastPublishedScreenRevision = screenRevision;
        if (mLastAckedScreenRevision >= 0 && screenRevision > mLastAckedScreenRevision) {
            long revDelta = screenRevision - mLastAckedScreenRevision;
            if (revDelta > 1) mCoalescedRevisionCount += revDelta - 1;
        }
    }

    /**
     * Record that the renderer successfully completed the most recently published frame.
     * This acks the revision carried by that frame.
     */
    public synchronized void ack(long screenRevision) {
        mDrawnFrameCount++;
        mLastDrawnScreenRevision = screenRevision;
        mLastAckedScreenRevision = screenRevision;
    }

    /** Record that a published frame did not complete rendering. */
    public synchronized void drop() {
        mDroppedFrameCount++;
    }

    public synchronized long getPublishedFrameCount() {
        return mPublishedFrameCount;
    }

    public synchronized long getLastPublishedScreenRevision() {
        return mLastPublishedScreenRevision;
    }

    public synchronized long getDrawnFrameCount() {
        return mDrawnFrameCount;
    }

    public synchronized long getLastDrawnScreenRevision() {
        return mLastDrawnScreenRevision;
    }

    public synchronized long getDroppedFrameCount() {
        return mDroppedFrameCount;
    }

    public synchronized long getCoalescedRevisionCount() {
        return mCoalescedRevisionCount;
    }

    public synchronized long getLastAckedScreenRevision() {
        return mLastAckedScreenRevision;
    }

    /**
     * Return an atomic snapshot of all current counters under a single lock.
     *
     * <p>This is intended for diagnostics and tests. Reading individual getters
     * back-to-back is unsafe under concurrency: the mailbox may publish and/or
     * ack a frame between reads, producing transient states such as
     * {@code lastAckedScreenRevision > lastPublishedScreenRevision}.</p>
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                mPublishedFrameCount,
                mLastPublishedScreenRevision,
                mDrawnFrameCount,
                mLastDrawnScreenRevision,
                mDroppedFrameCount,
                mCoalescedRevisionCount,
                mLastAckedScreenRevision);
    }

    /** Immutable snapshot of {@link RenderFrameMetrics} counters. */
    public static final class Snapshot {
        public final long publishedFrameCount;
        public final long lastPublishedScreenRevision;
        public final long drawnFrameCount;
        public final long lastDrawnScreenRevision;
        public final long droppedFrameCount;
        public final long coalescedRevisionCount;
        public final long lastAckedScreenRevision;

        Snapshot(long publishedFrameCount, long lastPublishedScreenRevision,
                 long drawnFrameCount, long lastDrawnScreenRevision,
                 long droppedFrameCount, long coalescedRevisionCount,
                 long lastAckedScreenRevision) {
            this.publishedFrameCount = publishedFrameCount;
            this.lastPublishedScreenRevision = lastPublishedScreenRevision;
            this.drawnFrameCount = drawnFrameCount;
            this.lastDrawnScreenRevision = lastDrawnScreenRevision;
            this.droppedFrameCount = droppedFrameCount;
            this.coalescedRevisionCount = coalescedRevisionCount;
            this.lastAckedScreenRevision = lastAckedScreenRevision;
        }
    }

    /**
     * Invariant check for cumulative lifecycle metrics.
     *
     * <p>Drawn is a render-attempt count, so it may exceed published when a frame
     * is redrawn. Dropped also includes mailbox replacement and renderer failures;
     * it is therefore not an exclusive partition with drawn.</p>
     */
    public synchronized boolean isConsistent() {
        return mPublishedFrameCount >= 0
            && mDrawnFrameCount >= 0
            && mDroppedFrameCount >= 0
            && mCoalescedRevisionCount >= 0
            && mLastAckedScreenRevision <= mLastPublishedScreenRevision;
    }
}
