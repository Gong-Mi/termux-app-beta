package com.termux.view;

import com.termux.terminal.FrameRevision;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Latest-only mailbox with an explicit session/target and projection identity.
 *
 * <p>This is the backend-neutral consumer boundary. It rejects frames from a
 * detached session or recreated target before they can replace the current
 * slot, and retains the identity alongside the frame for later ack stages.</p>
 */
public final class TerminalFrameConsumerMailbox<T extends FrameRevision> {

    public enum SubmitResult {
        ACCEPTED,
        REJECTED_INCOMPATIBLE,
        REJECTED_STALE
    }

    /** Observable milestones; unknown later stages must not be inferred. */
    public enum AckStage {
        ACCEPTED,
        RASTERED,
        SUBMITTED,
        PRESENTED
    }

    public enum AckResult {
        RECORDED,
        REJECTED_INCOMPATIBLE,
        REJECTED_ORDER
    }

    public static final class Entry<T> {
        public final T frame;
        public final TerminalFrameIdentity identity;

        private Entry(T frame, TerminalFrameIdentity identity) {
            this.frame = frame;
            this.identity = identity;
        }
    }

    private final RenderFrameMetrics mMetrics;
    private final long mSessionGeneration;
    private final long mTargetGeneration;
    private final AtomicReference<Entry<T>> mSlot = new AtomicReference<>();
    private final AtomicReference<TerminalFrameIdentity> mLastAccepted = new AtomicReference<>();
    private final AtomicLong mRejectedIncompatible = new AtomicLong();
    private final AtomicLong mRejectedStale = new AtomicLong();
    private final AtomicLong mRejectedAckIncompatible = new AtomicLong();
    private final AtomicLong mRejectedAckOrder = new AtomicLong();
    /** Accepted/current and acquired/in-flight identities awaiting presentation. */
    private final Map<TerminalFrameIdentity, AckStage> mAckStages = new HashMap<>();

    public TerminalFrameConsumerMailbox(RenderFrameMetrics metrics,
                                        long sessionGeneration, long targetGeneration) {
        mMetrics = metrics;
        mSessionGeneration = sessionGeneration;
        mTargetGeneration = targetGeneration;
    }

    /** Submit a frame if it belongs to this target and advances both revisions. */
    public synchronized SubmitResult submit(T frame, TerminalFrameIdentity identity) {
        if (frame == null || identity == null) {
            throw new IllegalArgumentException("frame and identity must be non-null");
        }
        if (identity.sessionGeneration != mSessionGeneration
            || identity.targetGeneration != mTargetGeneration) {
            mRejectedIncompatible.incrementAndGet();
            return SubmitResult.REJECTED_INCOMPATIBLE;
        }

        TerminalFrameIdentity previous = mLastAccepted.get();
        if (previous != null && !identity.isNewerThan(previous)) {
            mRejectedStale.incrementAndGet();
            return SubmitResult.REJECTED_STALE;
        }

        Entry<T> entry = new Entry<>(frame, identity);
        Entry<T> replaced = mSlot.getAndSet(entry);
        mLastAccepted.set(identity);
        // ACCEPTED is implicit upon submission; recordAck advances from here.
        mAckStages.put(identity, AckStage.ACCEPTED);
        if (replaced != null) mAckStages.remove(replaced.identity);
        if (replaced != null) mMetrics.drop();
        mMetrics.publish(frame.getScreenRevision());
        return SubmitResult.ACCEPTED;
    }

    /**
     * Record the next evidence-based milestone for an identity.
     *
     * <p>Stages must advance contiguously: ACCEPTED is set by submit; the first
     * call must be RASTERED, then SUBMITTED. PRESENTED is not allowed without
     * FrameTimeline/present-fence evidence and is rejected as unknown.</p>
     */
    public synchronized AckResult recordAck(TerminalFrameIdentity identity, AckStage stage) {
        if (identity == null || stage == null
            || identity.sessionGeneration != mSessionGeneration
            || identity.targetGeneration != mTargetGeneration) {
            mRejectedAckIncompatible.incrementAndGet();
            return AckResult.REJECTED_INCOMPATIBLE;
        }
        if (!mAckStages.containsKey(identity)) {
            mRejectedAckOrder.incrementAndGet();
            return AckResult.REJECTED_ORDER;
        }
        // PRESENTED requires external evidence; do not infer it.
        if (stage == AckStage.PRESENTED) {
            mRejectedAckOrder.incrementAndGet();
            return AckResult.REJECTED_ORDER;
        }
        AckStage current = mAckStages.get(identity);
        if (current == AckStage.SUBMITTED) {
            mRejectedAckOrder.incrementAndGet();
            return AckResult.REJECTED_ORDER;
        }
        AckStage expected = AckStage.values()[current.ordinal() + 1];
        if (stage != expected) {
            mRejectedAckOrder.incrementAndGet();
            return AckResult.REJECTED_ORDER;
        }
        mAckStages.put(identity, stage);
        return AckResult.RECORDED;
    }

    public long getRejectedIncompatibleCount() {
        return mRejectedIncompatible.get();
    }

    public long getRejectedStaleCount() {
        return mRejectedStale.get();
    }

    public long getRejectedAckIncompatibleCount() {
        return mRejectedAckIncompatible.get();
    }

    public long getRejectedAckOrderCount() {
        return mRejectedAckOrder.get();
    }

    /** Acquire and clear the latest accepted frame and its identity. */
    public synchronized Entry<T> acquireLatest() {
        Entry<T> entry = mSlot.getAndSet(null);
        return entry;
    }

    /** Peek without acquiring. */
    public synchronized Entry<T> peekLatest() {
        return mSlot.get();
    }
}
