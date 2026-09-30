package com.termux.view;

import com.termux.terminal.FrameRevision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TerminalFrameConsumerMailboxTest {

    private static final class TestFrame implements FrameRevision {
        private final long revision;
        TestFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    @Test
    public void rejectsFrameFromOldSessionOrTarget() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);

        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.REJECTED_INCOMPATIBLE,
            mailbox.submit(new TestFrame(12L), new TerminalFrameIdentity(6L, 3L, 12L, 1L)));
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.REJECTED_INCOMPATIBLE,
            mailbox.submit(new TestFrame(12L), new TerminalFrameIdentity(7L, 2L, 12L, 1L)));
        assertNull(mailbox.acquireLatest());
    }

    @Test
    public void acceptsProjectionAdvanceWithoutModelAdvance() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        TestFrame first = new TestFrame(12L);
        TestFrame projection = new TestFrame(12L);

        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED,
            mailbox.submit(first, new TerminalFrameIdentity(7L, 3L, 12L, 4L)));
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED,
            mailbox.submit(projection, new TerminalFrameIdentity(7L, 3L, 12L, 5L)));

        TerminalFrameConsumerMailbox.Entry<TestFrame> entry = mailbox.acquireLatest();
        assertSame(projection, entry.frame);
        assertEquals(new TerminalFrameIdentity(7L, 3L, 12L, 5L), entry.identity);
    }

    @Test
    public void rejectsRevisionRollbackEvenWhenTheOtherRevisionAdvances() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);

        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED,
            mailbox.submit(new TestFrame(12L), new TerminalFrameIdentity(7L, 3L, 12L, 8L)));
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.REJECTED_STALE,
            mailbox.submit(new TestFrame(11L), new TerminalFrameIdentity(7L, 3L, 11L, 9L)));
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.REJECTED_STALE,
            mailbox.submit(new TestFrame(12L), new TerminalFrameIdentity(7L, 3L, 12L, 7L)));
    }
}
