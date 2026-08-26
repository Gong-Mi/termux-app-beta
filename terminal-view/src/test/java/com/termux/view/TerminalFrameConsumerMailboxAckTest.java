package com.termux.view;

import com.termux.terminal.FrameRevision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TerminalFrameConsumerMailboxAckTest {

    private static final class TestFrame implements FrameRevision {
        private final long revision;
        TestFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    @Test
    public void recordsAckStagesOnlyInOrderForTheAcceptedIdentity() {
        RenderFrameMetrics metrics = new RenderFrameMetrics();
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(metrics, 7L, 3L);
        TerminalFrameIdentity identity = new TerminalFrameIdentity(7L, 3L, 12L, 4L);
        mailbox.submit(new TestFrame(12L), identity);

        assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
            mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.ACCEPTED));
        assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
            mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.RASTERED));
        assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
            mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.SUBMITTED));
        assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
            mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.PRESENTED));
    }

    @Test
    public void rejectsAckFromAnotherTargetAndStageRegression() {
        RenderFrameMetrics metrics = new RenderFrameMetrics();
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(metrics, 7L, 3L);
        TerminalFrameIdentity identity = new TerminalFrameIdentity(7L, 3L, 12L, 4L);
        mailbox.submit(new TestFrame(12L), identity);

        assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
            mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.ACCEPTED));
        assertEquals(TerminalFrameConsumerMailbox.AckResult.REJECTED_ORDER,
            mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.ACCEPTED));
        assertEquals(TerminalFrameConsumerMailbox.AckResult.REJECTED_INCOMPATIBLE,
            mailbox.recordAck(new TerminalFrameIdentity(6L, 3L, 12L, 4L),
                TerminalFrameConsumerMailbox.AckStage.RASTERED));
    }
}
