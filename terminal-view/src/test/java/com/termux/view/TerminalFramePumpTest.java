package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.FrameRevision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Push-style half of the #51 consumer boundary (#52 SurfaceView prerequisite):
 * {@code TerminalFramePump} forwards the latest accepted frame from a
 * {@link TerminalFrameConsumerMailbox} to a backend sink on a caller-chosen
 * executor (Surface/GLES render thread in production, manual executor in tests).
 *
 * Contract under test:
 * - latest-only: coalesced frames collapse; each accepted frame is delivered at
 *   most once and intermediates are never re-queued;
 * - identity survives the handoff so backend ack stages stay correlatable;
 * - generation-safe detach: after {@code detach()}, queued and future produces
 *   deliver nothing, deterministically, without any thread timing dependence;
 * - executor rejection is retryable and mid-drain production is not lost.
 */
public class TerminalFramePumpTest {

    private static final class TestFrame implements FrameRevision {
        private final long revision;
        TestFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    /** Single-threaded manual executor: queuing is observable before running. */
    private static final class ManualExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();

        @Override public void execute(Runnable command) { queue.add(command); }

        int runAll() {
            int ran = 0;
            while (!queue.isEmpty()) {
                Runnable task = queue.remove(0);
                task.run();
                ran++;
            }
            return ran;
        }
    }

    private static final class RecordingSink implements TerminalFramePump.Sink<TestFrame> {
        final List<TerminalFrameConsumerMailbox.Entry<TestFrame>> entries = new ArrayList<>();

        @Override public void accept(TerminalFrameConsumerMailbox.Entry<TestFrame> entry) {
            entries.add(entry);
        }
    }

    @Test
    public void forwardsOnlyLatestAcceptedEntryWithIdentity() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        TerminalFramePump<TestFrame> pump = new TerminalFramePump<>(mailbox, sink, executor);

        submit(mailbox, 10L);
        pump.requestDelivery();
        submit(mailbox, 11L);
        pump.requestDelivery();
        submit(mailbox, 12L);
        pump.requestDelivery();

        assertEquals("produces must coalesce onto ONE pending drain", 1, executor.queue.size());

        int ran = executor.runAll();
        assertTrue("delivered cycle count must not exceed schedule count", ran <= 3);
        assertEquals("coalesced intermediates must never be delivered", 1, sink.entries.size());
        TerminalFrameConsumerMailbox.Entry<TestFrame> delivered = sink.entries.get(0);
        assertEquals(12L, delivered.frame.getScreenRevision());
        assertNotNull(delivered.identity);

        // Identity correlation: the delivered frame's ack ladder still starts cleanly.
        assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
            mailbox.recordAck(delivered.identity, TerminalFrameConsumerMailbox.AckStage.RASTERED));
    }

    @Test
    public void detachStopsQueuedAndFutureDelivery() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingSink sink = new RecordingSink();
        ManualExecutor executor = new ManualExecutor();
        TerminalFramePump<TestFrame> pump = new TerminalFramePump<>(mailbox, sink, executor);

        submit(mailbox, 10L);
        pump.requestDelivery();
        assertEquals(1, executor.queue.size());

        pump.detach();
        executor.runAll();
        assertTrue("queued task must complete without delivering after detach",
            sink.entries.isEmpty());

        submit(mailbox, 11L);
        pump.requestDelivery();
        executor.runAll();
        assertTrue("frames produced after detach are not pushed", sink.entries.isEmpty());
        assertFalse(pump.isAttached());
    }

    @Test
    public void executorRejectionIsRetriedByNextRequest() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingSink sink = new RecordingSink();
        final boolean[] broken = {true};
        Executor flaky = command -> {
            if (broken[0]) throw new IllegalStateException("executor saturated");
            command.run();
        };
        TerminalFramePump<TestFrame> pump = new TerminalFramePump<>(mailbox, sink, flaky);

        submit(mailbox, 10L);
        assertThrows(IllegalStateException.class, pump::requestDelivery);

        broken[0] = false;
        pump.requestDelivery();
        assertEquals("schedule flag must reset on rejected execute", 1, sink.entries.size());
        assertEquals(10L, sink.entries.get(0).frame.getScreenRevision());
    }

    @Test
    public void frameProducedDuringDrainIsNotLost() {
        TerminalFrameConsumerMailbox<TestFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        ManualExecutor executor = new ManualExecutor();
        List<TerminalFrameConsumerMailbox.Entry<TestFrame>> delivered = new ArrayList<>();
        TerminalFramePump<TestFrame> pump = new TerminalFramePump<>(mailbox,
            (TerminalFramePump.Sink<TestFrame>) delivered::add, executor);
        submit(mailbox, 10L);
        pump.requestDelivery();

        // Simulate a late producer racing the drain: while the FIRST delivery runs,
        // the model publishes F1 and asks for delivery again (flag still held).
        executor.queue.add(() -> {
            submit(mailbox, 11L);
            pump.requestDelivery();
        });

        executor.runAll();
        assertEquals("mid-drain produce must surface, not vanish", 2, delivered.size());
        assertEquals(10L, delivered.get(0).frame.getScreenRevision());
        assertEquals(11L, delivered.get(1).frame.getScreenRevision());
    }

    private static void submit(TerminalFrameConsumerMailbox<TestFrame> mailbox, long modelRevision) {
        TerminalFrameIdentity identity = new TerminalFrameIdentity(
            7L, 3L, modelRevision, modelRevision);
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED,
            mailbox.submit(new TestFrame(modelRevision), identity));
    }
}
