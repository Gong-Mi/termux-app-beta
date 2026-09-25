package com.termux.view;

import com.termux.terminal.FrameRevision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Issue #57 P1 stats conformance: rejection, drop, accepted, rastered, submitted
 * must correlate to the same identity/revision — an unambiguous, merged snapshot
 * needs the per-identity ack chain to be observable, not just aggregate counters.
 *
 * <p>The mailbox owns the ack ladder (ACCEPTED at submit, RASTERED/SUBMITTED via
 * recordAck). This test pins the per-identity view of that chain and the invariants
 * the merged diagnostics must be able to rely on:</p>
 *
 * <ul>
 *   <li>a frame's current stage is queryable by the same identity that submitted it;</li>
 *   <li>the stage survives mailbox slot replacement (latest-only drop of an
 *       UNCONSUMED frame must not lose the replaced frame's already-recorded
 *       stages — dropped frames may legitimately have RASTERED/SUBMITTED acks
 *       recorded before their replacement);</li>
 *   <li>identity-qualified rejections (incompatible/order) never corrupt the
 *       accepted chain;</li>
 *   <li>the accepted identity chain is queryable in order, so verifiers can walk
 *       published→rastered→submitted per identity without relying on aggregate
 *       equality between parser and render counters (which are separate snapshots).</li>
 * </ul>
 */
public class RenderStatsIdentityChainTest {

    private static final class TestFrame implements FrameRevision {
        private final long revision;
        TestFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    private static TerminalFrameConsumerMailbox<TestFrame> mailbox() {
        return new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
    }

    private static TerminalFrameIdentity identity(long modelRevision, long projectionRevision) {
        return new TerminalFrameIdentity(7L, 3L, modelRevision, projectionRevision);
    }

    @Test
    public void stageIsQueryableByTheSubmittingIdentity() {
        TerminalFrameConsumerMailbox<TestFrame> mbox = mailbox();
        TerminalFrameIdentity id = identity(10L, 1L);
        mbox.submit(new TestFrame(10L), id);
        assertEquals(TerminalFrameConsumerMailbox.AckStage.ACCEPTED, mbox.ackStageFor(id));
        mbox.recordAck(id, TerminalFrameConsumerMailbox.AckStage.RASTERED);
        assertEquals(TerminalFrameConsumerMailbox.AckStage.RASTERED, mbox.ackStageFor(id));
        mbox.recordAck(id, TerminalFrameConsumerMailbox.AckStage.SUBMITTED);
        assertEquals(TerminalFrameConsumerMailbox.AckStage.SUBMITTED, mbox.ackStageFor(id));
    }

    @Test
    public void unknownIdentityReportsNullStageNotAccept() {
        TerminalFrameConsumerMailbox<TestFrame> mbox = mailbox();
        assertNull(mbox.ackStageFor(identity(99L, 99L)));
    }

    @Test
    public void replacedFramesKeepTheirRecordedStages() {
        // Latest-only replacement drops an unconsumed frame from the SLOT, but a
        // verifier correlating published->rastered->submitted must still see the
        // replaced frame's recorded stages: the mailbox drop and the ack ladder
        // are different lifecycles.
        TerminalFrameConsumerMailbox<TestFrame> mbox = mailbox();
        TerminalFrameIdentity first = identity(10L, 1L);
        mbox.submit(new TestFrame(10L), first);
        mbox.recordAck(first, TerminalFrameConsumerMailbox.AckStage.RASTERED);
        mbox.recordAck(first, TerminalFrameConsumerMailbox.AckStage.SUBMITTED);

        TerminalFrameIdentity second = identity(11L, 2L);
        mbox.submit(new TestFrame(11L), second);  // replaces first in the slot

        assertEquals(TerminalFrameConsumerMailbox.AckStage.SUBMITTED, mbox.ackStageFor(first));
        assertEquals(TerminalFrameConsumerMailbox.AckStage.ACCEPTED, mbox.ackStageFor(second));
        assertEquals("mailbox drop counted once", 1L, mbox.snapshot().droppedFrames);
    }

    @Test
    public void rejectionDoesNotCorruptTheAcceptedChain() {
        TerminalFrameConsumerMailbox<TestFrame> mbox = mailbox();
        TerminalFrameIdentity good = identity(10L, 1L);
        mbox.submit(new TestFrame(10L), good);

        // Foreign-generation identity: rejected as incompatible.
        TerminalFrameIdentity foreign = new TerminalFrameIdentity(8L, 3L, 11L, 1L);
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.REJECTED_INCOMPATIBLE,
            mbox.submit(new TestFrame(11L), foreign));
        // Stale identity (same generations, older revisions): rejected as stale.
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.REJECTED_STALE,
            mbox.submit(new TestFrame(9L), identity(9L, 0L)));
        // Out-of-order ack on a never-accepted identity: rejected.
        assertEquals(TerminalFrameConsumerMailbox.AckResult.REJECTED_ORDER,
            mbox.recordAck(identity(12L, 5L), TerminalFrameConsumerMailbox.AckStage.RASTERED));

        assertEquals(TerminalFrameConsumerMailbox.AckStage.ACCEPTED, mbox.ackStageFor(good));
        assertEquals(1L, mbox.getRejectedIncompatibleCount());
        assertEquals(1L, mbox.getRejectedStaleCount());
        assertEquals(1L, mbox.getRejectedAckOrderCount());
    }

    @Test
    public void acceptedChainIsQueryableInSubmissionOrder() {
        TerminalFrameConsumerMailbox<TestFrame> mbox = mailbox();
        List<TerminalFrameIdentity> ids = new ArrayList<>();
        for (long rev = 10L; rev <= 13L; rev++) {
            TerminalFrameIdentity id = identity(rev, rev - 9L);
            ids.add(id);
            mbox.submit(new TestFrame(rev), id);
        }
        List<TerminalFrameIdentity> chain = mbox.acceptedIdentities();
        assertEquals(ids.size(), chain.size());
        for (int i = 0; i < ids.size(); i++) {
            assertEquals("accepted chain preserves submission order",
                ids.get(i), chain.get(i));
        }
    }
}
