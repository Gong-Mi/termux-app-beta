package com.termux.view;

/**
 * Immutable snapshot of consumer rendering counters.
 *
 * <p>Stages follow the consumer pipeline: published → rastered → submitted → presented.
 * Not every backend can observe all stages; unobserved counters remain zero.</p>
 */
public final class RenderStats {

    public final long publishedFrames;
    public final long drawnFrames;
    public final long droppedFrames;
    public final long rasteredFrames;
    public final long submittedFrames;
    public final long presentedFrames;
    public final long rejectedIncompatibleFrames;
    public final long rejectedStaleFrames;
    public final long rejectedAckIncompatibleFrames;
    public final long rejectedAckOrderFrames;
    public final long lastPublishedScreenRevision;
    public final long lastDrawnScreenRevision;
    public final long coalescedRevisions;

    public RenderStats(long publishedFrames, long drawnFrames, long droppedFrames,
                       long rasteredFrames, long submittedFrames, long presentedFrames,
                       long rejectedIncompatibleFrames, long rejectedStaleFrames,
                       long rejectedAckIncompatibleFrames, long rejectedAckOrderFrames,
                       long lastPublishedScreenRevision, long lastDrawnScreenRevision,
                       long coalescedRevisions) {
        this.publishedFrames = publishedFrames;
        this.drawnFrames = drawnFrames;
        this.droppedFrames = droppedFrames;
        this.rasteredFrames = rasteredFrames;
        this.submittedFrames = submittedFrames;
        this.presentedFrames = presentedFrames;
        this.rejectedIncompatibleFrames = rejectedIncompatibleFrames;
        this.rejectedStaleFrames = rejectedStaleFrames;
        this.rejectedAckIncompatibleFrames = rejectedAckIncompatibleFrames;
        this.rejectedAckOrderFrames = rejectedAckOrderFrames;
        this.lastPublishedScreenRevision = lastPublishedScreenRevision;
        this.lastDrawnScreenRevision = lastDrawnScreenRevision;
        this.coalescedRevisions = coalescedRevisions;
    }

    /**
     * Convenience constructor for callers that only track the legacy
     * published/drawn/dropped counters. Stage and rejection counters are zeroed.
     */
    public RenderStats(long publishedFrames, long drawnFrames, long droppedFrames,
                       long lastPublishedScreenRevision, long lastDrawnScreenRevision,
                       long coalescedRevisions) {
        this(publishedFrames, drawnFrames, droppedFrames,
             0L, 0L, 0L,
             0L, 0L, 0L, 0L,
             lastPublishedScreenRevision, lastDrawnScreenRevision, coalescedRevisions);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RenderStats)) return false;
        RenderStats other = (RenderStats) o;
        return publishedFrames == other.publishedFrames
            && drawnFrames == other.drawnFrames
            && droppedFrames == other.droppedFrames
            && rasteredFrames == other.rasteredFrames
            && submittedFrames == other.submittedFrames
            && presentedFrames == other.presentedFrames
            && rejectedIncompatibleFrames == other.rejectedIncompatibleFrames
            && rejectedStaleFrames == other.rejectedStaleFrames
            && rejectedAckIncompatibleFrames == other.rejectedAckIncompatibleFrames
            && rejectedAckOrderFrames == other.rejectedAckOrderFrames
            && lastPublishedScreenRevision == other.lastPublishedScreenRevision
            && lastDrawnScreenRevision == other.lastDrawnScreenRevision
            && coalescedRevisions == other.coalescedRevisions;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(publishedFrames);
        result = 31 * result + Long.hashCode(drawnFrames);
        result = 31 * result + Long.hashCode(droppedFrames);
        result = 31 * result + Long.hashCode(rasteredFrames);
        result = 31 * result + Long.hashCode(submittedFrames);
        result = 31 * result + Long.hashCode(presentedFrames);
        result = 31 * result + Long.hashCode(rejectedIncompatibleFrames);
        result = 31 * result + Long.hashCode(rejectedStaleFrames);
        result = 31 * result + Long.hashCode(rejectedAckIncompatibleFrames);
        result = 31 * result + Long.hashCode(rejectedAckOrderFrames);
        result = 31 * result + Long.hashCode(lastPublishedScreenRevision);
        result = 31 * result + Long.hashCode(lastDrawnScreenRevision);
        result = 31 * result + Long.hashCode(coalescedRevisions);
        return result;
    }

    @Override
    public String toString() {
        return "RenderStats{"
            + "published=" + publishedFrames
            + ", drawn=" + drawnFrames
            + ", dropped=" + droppedFrames
            + ", rastered=" + rasteredFrames
            + ", submitted=" + submittedFrames
            + ", presented=" + presentedFrames
            + ", rejectedIncompatible=" + rejectedIncompatibleFrames
            + ", rejectedStale=" + rejectedStaleFrames
            + ", rejectedAckIncompatible=" + rejectedAckIncompatibleFrames
            + ", rejectedAckOrder=" + rejectedAckOrderFrames
            + ", lastPublishedRevision=" + lastPublishedScreenRevision
            + ", lastDrawnRevision=" + lastDrawnScreenRevision
            + ", coalesced=" + coalescedRevisions
            + '}';
    }

    /**
     * Merge a mailbox-side snapshot (published/drawn/dropped/rejected) with a consumer-side
     * snapshot (rastered/submitted/presented). The legacy counters and revision fields are taken
     * from the mailbox side because it owns the authoritative {@link RenderFrameMetrics}.
     */
    public static RenderStats merge(RenderStats mailbox, RenderStats consumer) {
        return new RenderStats(
            mailbox.publishedFrames,
            mailbox.drawnFrames,
            mailbox.droppedFrames,
            consumer.rasteredFrames,
            consumer.submittedFrames,
            consumer.presentedFrames,
            mailbox.rejectedIncompatibleFrames,
            mailbox.rejectedStaleFrames,
            mailbox.rejectedAckIncompatibleFrames,
            mailbox.rejectedAckOrderFrames,
            mailbox.lastPublishedScreenRevision,
            mailbox.lastDrawnScreenRevision,
            mailbox.coalescedRevisions);
    }
}
