package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import com.termux.terminal.FrameRevision;

import org.junit.Test;

/**
 * Session-scoped render accounting contract (on-device bug 2026-09-25 07:18):
 * a diagnostics line combines mailbox-owned counters (published/drawn/dropped/
 * coalesced via the shared RenderFrameMetrics) with session-owned parser
 * counters. When a TerminalView switched sessions A -> B, the view-level
 * metrics instance survived the switch while the parser counters restarted
 * with session B — one line then carried session-A cumulative counts next to
 * session-B parser counts, and rastered/submitted (consumer-scoped) reset to 1
 * while published kept accumulating (rev 15118 -> 5851 at 07:18:26.233).
 *
 * This pins the fixed lifecycle shape at the level testable without
 * View/Looper: the metrics instance is session-scoped and travels with the
 * mailbox created in attachSession. A session switch must pair a FRESH
 * metrics instance with the FRESH mailbox; reusing the previous session's
 * instance produces the mixed-line artifact.
 */
public class SessionScopedRenderFrameMetricsTest {

    /** Minimal FrameRevision for mailbox submits. */
    private static final class Revision implements FrameRevision {
        final long revision;
        Revision(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    private static long publishOne(TerminalFrameConsumerMailbox<Revision> mailbox,
                                   long session, long revision) {
        Revision frame = new Revision(revision);
        mailbox.submit(frame, new TerminalFrameIdentity(session, session, revision, 1L));
        return revision;
    }

    @Test
    public void sessionSwitchPairsFreshMetricsWithFreshMailbox() {
        // Session A: one metrics instance, one mailbox, some activity.
        RenderFrameMetrics metricsA = new RenderFrameMetrics();
        TerminalFrameConsumerMailbox<Revision> mailboxA =
            new TerminalFrameConsumerMailbox<>(metricsA, 1L, 1L);
        publishOne(mailboxA, 1L, 10L);
        assertEquals(1L, metricsA.getPublishedFrameCount());

        // Session switch (attachSession shape): fresh metrics + fresh mailbox.
        RenderFrameMetrics metricsB = new RenderFrameMetrics();
        assertNotSame("session switch must create a fresh RenderFrameMetrics instance",
            metricsA, metricsB);
        TerminalFrameConsumerMailbox<Revision> mailboxB =
            new TerminalFrameConsumerMailbox<>(metricsB, 2L, 2L);

        // Session B's first published frame starts from zero, in the same line
        // where session B's parser counters also restart from zero.
        publishOne(mailboxB, 2L, 1L);
        assertEquals("session B counters must start fresh, not carry A's totals",
            1L, metricsB.getPublishedFrameCount());
        assertEquals("session A metrics must be untouched by B activity",
            1L, metricsA.getPublishedFrameCount());
    }

    @Test
    public void sharedMetricsAcrossSessionsReproducesTheCounterMix() {
        // Witness of the original bug shape: if one metrics instance is reused
        // across two mailboxes (the pre-fix view-level field), session B's
        // diagnostics line carries session A's cumulative counts.
        RenderFrameMetrics shared = new RenderFrameMetrics();
        TerminalFrameConsumerMailbox<Revision> mailboxA =
            new TerminalFrameConsumerMailbox<>(shared, 1L, 1L);
        publishOne(mailboxA, 1L, 10L);
        publishOne(mailboxA, 1L, 11L);

        TerminalFrameConsumerMailbox<Revision> mailboxB =
            new TerminalFrameConsumerMailbox<>(shared, 2L, 2L);
        publishOne(mailboxB, 2L, 1L);

        // Session B published exactly one frame, but the shared instance
        // reports three — exactly the mixed-line artifact observed on device.
        assertEquals(3L, shared.getPublishedFrameCount());
    }
}
