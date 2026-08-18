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

    /** Invariant check: published >= drawn + dropped, and acked <= lastPublishedRev. */
    public synchronized boolean isConsistent() {
        return mPublishedFrameCount >= mDrawnFrameCount + mDroppedFrameCount
            && mLastAckedScreenRevision <= mLastPublishedScreenRevision;
    }
}
