package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.FrameRevision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conformance for the join half of #51's completion condition and #52's first
 * kill criterion ("旧线程 join 与 stale generation 拒绝"):
 *
 * - {@code detachAndJoin} blocks until every executor task scheduled before the
 *   detach decision has finished RUNNING, including an in-flight sink accept;
 *   a frame whose delivery started before the flag flip completes before join
 *   returns — so no pump work survives past a clean join;
 * - on timeout join reports {@code false} and can be retried;
 * - a pump detached while its drain is merely QUEUED delivers nothing once the
 *   executor finally runs that task;
 * - post-detach production is not pushed; the session A→B generation wall
 *   itself lives in the mailboxes, which reject cross-generation identities.
 */
public class TerminalFramePumpJoinTest {

    private static final class TestFrame implements FrameRevision {
        private final long revision;
        TestFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    private static TerminalFrameConsumerMailbox<TestFrame> mailbox(long session, long target) {
        return new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), session, target);
    }

    private static void submit(TerminalFrameConsumerMailbox<TestFrame> mbox,
                               long session, long target, long modelRevision) {
        TerminalFrameIdentity id = new TerminalFrameIdentity(session, target, modelRevision, modelRevision);
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED,
            mbox.submit(new TestFrame(modelRevision), id));
    }

    @Test
    public void joinReturnsOnlyAfterInFlightAcceptCompletes() throws Exception {
        TerminalFrameConsumerMailbox<TestFrame> mbox = mailbox(7L, 3L);
        List<TerminalFrameConsumerMailbox.Entry<TestFrame>> delivered = new ArrayList<>();
        AtomicLong deliveredAtNanos = new AtomicLong();
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TerminalFramePump<TestFrame> pump = new TerminalFramePump<>(mbox, entry -> {
                sinkEntered.countDown();
                try {
                    releaseSink.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delivered.add(entry);
                deliveredAtNanos.set(System.nanoTime());
            }, pool);

            submit(mbox, 7L, 3L, 10L);
            pump.requestDelivery();
            assertTrue(sinkEntered.await(5, TimeUnit.SECONDS));

            final AtomicBoolean joinResult = new AtomicBoolean(false);
            final CountDownLatch joinDone = new CountDownLatch(1);
            Thread joiner = new Thread(() -> {
                joinResult.set(pump.detachAndJoin(30_000L));
                joinDone.countDown();
            });
            joiner.start();

            // joiner must still be waiting while the sink holds the drain open.
            // Negative-direction assertion: a broken immediate-return join fails
            // here deterministically; load cannot flip it.
            assertFalse("join must wait for the in-flight accept",
                joinDone.await(150, TimeUnit.MILLISECONDS));

            releaseSink.countDown();
            assertTrue(joinDone.await(10, TimeUnit.SECONDS));
            assertTrue(joinResult.get());
            assertEquals("frame whose accept began before detach completes inside join",
                1, delivered.size());

            long joinEndedAtNanos = System.nanoTime();
            assertTrue(deliveredAtNanos.get() <= joinEndedAtNanos);
            assertFalse(pump.isAttached());
        } finally {
            releaseSink.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void joinTimesOutWhileBlockedThenSucceedsAfterRelease() throws Exception {
        TerminalFrameConsumerMailbox<TestFrame> mbox = mailbox(7L, 3L);
        List<TerminalFrameConsumerMailbox.Entry<TestFrame>> delivered = new ArrayList<>();
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TerminalFramePump<TestFrame> pump = new TerminalFramePump<>(mbox, entry -> {
                sinkEntered.countDown();
                try {
                    releaseSink.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delivered.add(entry);
            }, pool);

            submit(mbox, 7L, 3L, 11L);
            pump.requestDelivery();
            // Close the race deterministically: only assert the timeout once the
            // drain is provably inside the blocking accept.
            assertTrue(sinkEntered.await(5, TimeUnit.SECONDS));

            long startNanos = System.nanoTime();
            assertFalse(pump.detachAndJoin(250L));
            long waitedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue("timeout must be honored (waited " + waitedMillis + "ms)",
                waitedMillis >= 200 && waitedMillis < 5_000);

            releaseSink.countDown();
            assertTrue(pump.detachAndJoin(10_000L));
            assertEquals(1, delivered.size());
        } finally {
            releaseSink.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void queuedDrainAfterDetachDeliversNothing() {
        TerminalFrameConsumerMailbox<TestFrame> mbox = mailbox(7L, 3L);
        List<TerminalFrameConsumerMailbox.Entry<TestFrame>> delivered = new ArrayList<>();
        List<Runnable> queue = new ArrayList<>();
        java.util.concurrent.Executor manual = queue::add;
        TerminalFramePump<TestFrame> pump =
            new TerminalFramePump<>(mbox, (TerminalFramePump.Sink<TestFrame>) delivered::add, manual);

        submit(mbox, 7L, 3L, 12L);
        pump.requestDelivery();
        assertEquals(1, queue.size());

        pump.detach();
        assertTrue(pump.detachAndJoin(1_000L));

        for (Runnable task : new ArrayList<>(queue)) task.run();
        assertEquals("queued drain must not deliver after detach", 0, delivered.size());
        assertEquals("mailbox keeps the unconsumed frame", Long.valueOf(12L),
            Long.valueOf(mbox.peekLatest().frame.getScreenRevision()));
    }

    @Test
    public void postDetachProductionNotPushedAndGenerationWallHolds() {
        TerminalFrameConsumerMailbox<TestFrame> oldMbox = mailbox(7L, 3L);
        List<TerminalFrameConsumerMailbox.Entry<TestFrame>> delivered = new ArrayList<>();
        List<Runnable> queue = new ArrayList<>();
        TerminalFramePump<TestFrame> pump = new TerminalFramePump<>(oldMbox,
            (TerminalFramePump.Sink<TestFrame>) delivered::add, queue::add);

        pump.detach();
        submit(oldMbox, 7L, 3L, 13L);
        pump.requestDelivery();
        assertTrue("detached pump schedules nothing", queue.isEmpty());

        // Session A→B: new generation gets its own mailbox (as TerminalView does).
        TerminalFrameConsumerMailbox<TestFrame> newMbox = mailbox(8L, 4L);
        submit(newMbox, 8L, 4L, 20L);
        assertEquals(Long.valueOf(20L),
            Long.valueOf(newMbox.peekLatest().frame.getScreenRevision()));

        // Old-generation identity into the OLD mailbox is fine (it IS gen 7),
        // but a gen-7 identity into the NEW mailbox hits the generation wall.
        TerminalFrameIdentity stale = new TerminalFrameIdentity(7L, 3L, 21L, 21L);
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.REJECTED_INCOMPATIBLE,
            newMbox.submit(new TestFrame(21L), stale));
        assertEquals(Long.valueOf(20L),
            Long.valueOf(newMbox.peekLatest().frame.getScreenRevision()));

        // And the detached pump touched none of it.
        assertEquals(0, delivered.size());
    }
}
