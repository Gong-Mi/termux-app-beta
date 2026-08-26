package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TerminalFrameConsumerTest {

    private static final class StubConsumer implements TerminalFrameConsumer {
        long attachedGeneration = -1;
        RenderGeometry attachedGeometry;
        TerminalRenderFrame lastFrame;
        RenderDamage lastDamage;
        boolean detached;
        RenderStats stats = new RenderStats(0, 0, 0, 0, 0, 0);

        @Override
        public void attach(long renderGeneration, RenderGeometry geometry) {
            this.attachedGeneration = renderGeneration;
            this.attachedGeometry = geometry;
            this.detached = false;
        }

        @Override
        public void submit(TerminalRenderFrame frame, RenderDamage damage) {
            this.lastFrame = frame;
            this.lastDamage = damage;
        }

        @Override
        public void detach(long renderGeneration) {
            if (renderGeneration == attachedGeneration) {
                this.detached = true;
            }
        }

        @Override
        public RenderStats snapshot() {
            return stats;
        }
    }

    @Test
    public void attachBindsGenerationAndGeometry() {
        StubConsumer consumer = new StubConsumer();
        RenderGeometry geometry = new RenderGeometry(80, 24, 1080, 1920);
        consumer.attach(7L, geometry);
        assertEquals(7L, consumer.attachedGeneration);
        assertEquals(geometry, consumer.attachedGeometry);
        assertFalse(consumer.detached);
    }

    @Test
    public void submitReceivesFrameAndDamage() {
        StubConsumer consumer = new StubConsumer();
        consumer.attach(1L, new RenderGeometry(80, 24, 1080, 1920));

        TerminalRenderFrame frame = null; // no render needed for stub
        RenderDamage damage = new RenderDamage(false, false, false, false, false, false, 0, 24, 80);
        consumer.submit(frame, damage);

        assertNull(consumer.lastFrame);
        assertEquals(damage, consumer.lastDamage);
    }

    @Test
    public void detachOnlyMatchesAttachedGeneration() {
        StubConsumer consumer = new StubConsumer();
        consumer.attach(3L, new RenderGeometry(80, 24, 1080, 1920));
        consumer.detach(2L);
        assertFalse(consumer.detached);
        consumer.detach(3L);
        assertTrue(consumer.detached);
    }

    @Test
    public void snapshotReturnsCurrentStats() {
        StubConsumer consumer = new StubConsumer();
        consumer.stats = new RenderStats(10, 9, 1, 100, 99, 5);
        RenderStats snapshot = consumer.snapshot();
        assertNotNull(snapshot);
        assertEquals(10L, snapshot.publishedFrames);
        assertEquals(9L, snapshot.drawnFrames);
        assertEquals(1L, snapshot.droppedFrames);
        assertEquals(100L, snapshot.lastPublishedScreenRevision);
        assertEquals(99L, snapshot.lastDrawnScreenRevision);
        assertEquals(5L, snapshot.coalescedRevisions);
    }
}
