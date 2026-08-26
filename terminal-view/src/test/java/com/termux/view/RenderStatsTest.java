package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RenderStatsTest {

    @Test
    public void equalsAndHashCodeUseAllFields() {
        RenderStats a = new RenderStats(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        RenderStats b = new RenderStats(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void legacyConstructorZeroesStageAndRejectionCounters() {
        RenderStats stats = new RenderStats(10, 9, 1, 100, 99, 5);
        assertEquals(10L, stats.publishedFrames);
        assertEquals(9L, stats.drawnFrames);
        assertEquals(1L, stats.droppedFrames);
        assertEquals(0L, stats.rasteredFrames);
        assertEquals(0L, stats.submittedFrames);
        assertEquals(0L, stats.presentedFrames);
        assertEquals(0L, stats.rejectedIncompatibleFrames);
        assertEquals(0L, stats.rejectedStaleFrames);
        assertEquals(0L, stats.rejectedAckIncompatibleFrames);
        assertEquals(0L, stats.rejectedAckOrderFrames);
        assertEquals(100L, stats.lastPublishedScreenRevision);
        assertEquals(99L, stats.lastDrawnScreenRevision);
        assertEquals(5L, stats.coalescedRevisions);
    }

    @Test
    public void mergeTakesMailboxLegacyAndConsumerStages() {
        RenderStats mailbox = new RenderStats(
            10, 8, 2,
            0, 0, 0,
            1, 2, 3, 4,
            100L, 80L, 5L);
        RenderStats consumer = new RenderStats(
            0, 0, 0,
            7, 6, 0,
            0, 0, 0, 0,
            0L, 0L, 0L);
        RenderStats merged = RenderStats.merge(mailbox, consumer);
        assertEquals(10L, merged.publishedFrames);
        assertEquals(8L, merged.drawnFrames);
        assertEquals(2L, merged.droppedFrames);
        assertEquals(7L, merged.rasteredFrames);
        assertEquals(6L, merged.submittedFrames);
        assertEquals(0L, merged.presentedFrames);
        assertEquals(1L, merged.rejectedIncompatibleFrames);
        assertEquals(2L, merged.rejectedStaleFrames);
        assertEquals(3L, merged.rejectedAckIncompatibleFrames);
        assertEquals(4L, merged.rejectedAckOrderFrames);
        assertEquals(100L, merged.lastPublishedScreenRevision);
        assertEquals(80L, merged.lastDrawnScreenRevision);
        assertEquals(5L, merged.coalescedRevisions);
    }
}
