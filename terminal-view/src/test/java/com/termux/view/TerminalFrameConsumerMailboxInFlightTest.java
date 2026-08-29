package com.termux.view;

import com.termux.terminal.FrameRevision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TerminalFrameConsumerMailboxInFlightTest {

    private static final class TestFrame implements FrameRevision {
        private final long revision;
        TestFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    @Test
    public void inFlightOlderFrameCanAckAfterNewerFrameIsAccepted() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        TerminalFrameIdentity older = new TerminalFrameIdentity(7L, 3L, 12L, 4L);
        TerminalFrameIdentity newer = new TerminalFrameIdentity(7L, 3L, 13L, 5L);

        mailbox.submit(new TestFrame(12L), older);
        assertNotNull(mailbox.acquireLatest());
        mailbox.submit(new TestFrame(13L), newer);

        assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
            mailbox.recordAck(older, TerminalFrameConsumerMailbox.AckStage.RASTERED));
        assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
            mailbox.recordAck(older, TerminalFrameConsumerMailbox.AckStage.SUBMITTED));
    }
}
