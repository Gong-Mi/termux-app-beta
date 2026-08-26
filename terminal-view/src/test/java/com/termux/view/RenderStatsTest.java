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
}
