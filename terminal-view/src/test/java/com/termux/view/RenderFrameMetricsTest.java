package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RenderFrameMetricsTest {

    @Test
    public void initialStateIsEmpty() {
        RenderFrameMetrics m = new RenderFrameMetrics();
        assertEquals(0L, m.getPublishedFrameCount());
        assertEquals(-1L, m.getLastPublishedScreenRevision());
        assertEquals(0L, m.getDrawnFrameCount());
        assertEquals(-1L, m.getLastDrawnScreenRevision());
        assertEquals(0L, m.getDroppedFrameCount());
        assertEquals(0L, m.getCoalescedRevisionCount());
        assertEquals(-1L, m.getLastAckedScreenRevision());
        assertTrue(m.isConsistent());
    }

    @Test
    public void publishThenAckKeepsConsistency() {
        RenderFrameMetrics m = new RenderFrameMetrics();
        m.publish(1);
        assertEquals(1L, m.getPublishedFrameCount());
        assertEquals(1L, m.getLastPublishedScreenRevision());
        assertTrue(m.isConsistent());

        m.ack(1);
        assertEquals(1L, m.getDrawnFrameCount());
        assertEquals(1L, m.getLastDrawnScreenRevision());
        assertEquals(1L, m.getLastAckedScreenRevision());
        assertTrue(m.isConsistent());
    }

    @Test
    public void dropAfterPublishIsConsistent() {
        RenderFrameMetrics m = new RenderFrameMetrics();
        m.publish(1);
        m.drop();
        assertEquals(1L, m.getPublishedFrameCount());
        assertEquals(0L, m.getDrawnFrameCount());
        assertEquals(1L, m.getDroppedFrameCount());
        assertTrue(m.isConsistent());
    }

    @Test
    public void coalescedRevisionsAreCounted() {
        RenderFrameMetrics m = new RenderFrameMetrics();
        m.publish(1);
        m.ack(1);

        // Three model revisions (2,3,4) arrive before the next onDraw, so 2 are coalesced.
        m.publish(4);
        assertEquals(2L, m.getCoalescedRevisionCount());
        assertEquals(4L, m.getLastPublishedScreenRevision());
        assertEquals(1L, m.getLastAckedScreenRevision());
        assertTrue(m.isConsistent());

        m.ack(4);
        assertEquals(4L, m.getLastAckedScreenRevision());
        assertTrue(m.isConsistent());
    }

    @Test
    public void noCoalescingForSingleRevisionAdvance() {
        RenderFrameMetrics m = new RenderFrameMetrics();
        m.publish(1);
        m.ack(1);
        m.publish(2);
        m.ack(2);
        assertEquals(0L, m.getCoalescedRevisionCount());
        assertTrue(m.isConsistent());
    }

    @Test
    public void inFlightFramesAreAccounted() {
        RenderFrameMetrics m = new RenderFrameMetrics();
        m.publish(1);
        m.publish(2);
        assertTrue(m.isConsistent());
        assertEquals(2L, m.getPublishedFrameCount());
        assertEquals(0L, m.getDrawnFrameCount() + m.getDroppedFrameCount());
    }

    @Test
    public void redrawAndDropCountsAreNotAnExclusivePartition() {
        RenderFrameMetrics m = new RenderFrameMetrics();
        m.publish(1);
        m.ack(1);
        m.ack(1); // same frame may be drawn again on a later onDraw
        m.drop();  // mailbox replacement or renderer failure is separately cumulative
        assertEquals(1L, m.getPublishedFrameCount());
        assertEquals(2L, m.getDrawnFrameCount());
        assertEquals(1L, m.getDroppedFrameCount());
        assertTrue(m.isConsistent());
    }

    @Test
    public void consistencyDetectsOverAck() {
        RenderFrameMetrics m = new RenderFrameMetrics();
        m.publish(1);
        m.ack(2); // ack revision larger than published
        assertFalse(m.isConsistent());
    }
}
