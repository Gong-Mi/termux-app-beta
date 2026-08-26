package com.termux.view;

import com.termux.terminal.FrameRevision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TerminalFrameConsumerMailboxMetricsTest {

    private static final class TestFrame implements FrameRevision {
        private final long revision;
        TestFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    @Test
    public void countsRejectedGenerationAndStaleFrames() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        mailbox.submit(new TestFrame(1L), new TerminalFrameIdentity(7L, 3L, 1L, 1L));
        mailbox.submit(new TestFrame(2L), new TerminalFrameIdentity(6L, 3L, 2L, 2L));
        mailbox.submit(new TestFrame(1L), new TerminalFrameIdentity(7L, 3L, 1L, 1L));

        assertEquals(1L, mailbox.getRejectedIncompatibleCount());
        assertEquals(1L, mailbox.getRejectedStaleCount());
    }

    @Test
    public void countsRejectedAckStages() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        TerminalFrameIdentity identity = new TerminalFrameIdentity(7L, 3L, 1L, 1L);
        mailbox.submit(new TestFrame(1L), identity);
        mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.ACCEPTED);
        mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.ACCEPTED);
        mailbox.recordAck(new TerminalFrameIdentity(6L, 3L, 1L, 1L),
            TerminalFrameConsumerMailbox.AckStage.RASTERED);

        assertEquals(1L, mailbox.getRejectedAckOrderCount());
        assertEquals(1L, mailbox.getRejectedAckIncompatibleCount());
    }
}
