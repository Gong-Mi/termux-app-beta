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
    /** Submission order of accepted identities, for ordered per-identity chain queries. */
    private final java.util.ArrayDeque<TerminalFrameIdentity> mAcceptedOrder =
        new java.util.ArrayDeque<>();
    /** Cap on retained completed identities so diagnostics cannot leak memory. */
    private static final int MAX_RETAINED_IDENTITIES = 256;

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
        mAcceptedOrder.addLast(identity);
        // Replaced SLOT frames keep their recorded ack stages (drop and the ack
        // ladder are different lifecycles): a frame that was consumed/rastered and
        // then replaced still has RASTERED/SUBMITTED recorded on its identity, and
        // verifiers must be able to correlate that. Stale stages age out only via
        // the retained-order ring cap below.
        while (mAcceptedOrder.size() > MAX_RETAINED_IDENTITIES) {
            TerminalFrameIdentity evicted = mAcceptedOrder.pollFirst();
            if (evicted != null) mAckStages.remove(evicted);
        }
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

    /** Return a snapshot of mailbox-side counters. Stage counters are zero because the
     * mailbox does not observe raster/submit/present; it only tracks published/dropped
     * and the four rejection categories. */
    public RenderStats snapshot() {
        return new RenderStats(
            mMetrics.getPublishedFrameCount(),
            mMetrics.getDrawnFrameCount(),
            mMetrics.getDroppedFrameCount(),
            0L, 0L, 0L,
            mRejectedIncompatible.get(),
            mRejectedStale.get(),
            mRejectedAckIncompatible.get(),
            mRejectedAckOrder.get(),
            mMetrics.getLastPublishedScreenRevision(),
            mMetrics.getLastDrawnScreenRevision(),
            mMetrics.getCoalescedRevisionCount());
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

    /**
     * Current ack stage for an accepted identity, or null if the identity was never
     * accepted (or has aged out of the retained chain window). Correlating
     * published/rastered/submitted per identity — instead of comparing aggregate
     * counters across separately-sampled layers — is the #57 stats contract.
     */
    public synchronized AckStage ackStageFor(TerminalFrameIdentity identity) {
        return mAckStages.get(identity);
    }

    /**
     * Accepted identities in submission order, oldest first, within the retained
     * window. The list is a snapshot copy; mutation does not affect the mailbox.
     */
    public synchronized java.util.List<TerminalFrameIdentity> acceptedIdentities() {
        return new java.util.ArrayList<>(mAcceptedOrder);
    }
}
