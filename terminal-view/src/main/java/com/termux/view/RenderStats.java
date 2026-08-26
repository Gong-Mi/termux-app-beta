package com.termux.view;

/**
 * Immutable snapshot of consumer rendering counters.
 */
public final class RenderStats {

    public final long publishedFrames;
    public final long drawnFrames;
    public final long droppedFrames;
    public final long lastPublishedScreenRevision;
    public final long lastDrawnScreenRevision;
    public final long coalescedRevisions;

    public RenderStats(long publishedFrames, long drawnFrames, long droppedFrames,
                       long lastPublishedScreenRevision, long lastDrawnScreenRevision,
                       long coalescedRevisions) {
        this.publishedFrames = publishedFrames;
        this.drawnFrames = drawnFrames;
        this.droppedFrames = droppedFrames;
        this.lastPublishedScreenRevision = lastPublishedScreenRevision;
        this.lastDrawnScreenRevision = lastDrawnScreenRevision;
        this.coalescedRevisions = coalescedRevisions;
    }

    @Override
    public String toString() {
        return "RenderStats{"
            + "published=" + publishedFrames
            + ", drawn=" + drawnFrames
            + ", dropped=" + droppedFrames
            + ", lastPublishedRevision=" + lastPublishedScreenRevision
            + ", lastDrawnRevision=" + lastDrawnScreenRevision
            + ", coalesced=" + coalescedRevisions
            + '}';
    }
}
