package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Issue #57 P1 conformance: the shared {@link TerminalFrameConsumer} contract for
 * asynchronous backends, exercised against a fake async consumer (no Surface/GLES
 * backend required). Every backend-neutral rule that #57 demands of
 * {@code detachAndJoin} is asserted here:
 *
 * <ul>
 *   <li>true means NO frame is still being processed — a blocked backend makes the
 *       join wait (deterministic negative-direction assertion), and only the
 *       completed accept unblocks it;</li>
 *   <li>false on timeout is retryable; the drained task keeps running and a later
 *       join observes completion (task ownership stays with the consumer until a
 *       true return);</li>
 *   <li>after a true join the backend receives nothing more, whatever the producer does;</li>
 *   <li>foreign-generation submits are ignored without touching the backend;</li>
 *   <li>late mailbox acks for frames that completed inside the join still succeed:
 *       ack ownership belongs to the identity/mailbox ladder, not the consumer —
 *       detaching a consumer must never strand a frame's RASTERED/SUBMITTED stages.</li>
 * </ul>
 */
public class AsyncFrameConsumerContractTest {

    private static final class NoOpOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private static TerminalRenderFrame frame(long revisionSeed) {
        TerminalEmulator emulator = new TerminalEmulator(new NoOpOutput(), 8, 4, 13, 15, 8, null);
        byte[] input = ("frame" + revisionSeed).getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        TerminalModelFrame model = new TerminalModelFrame(emulator, 0, null, 0);
        return new TerminalRenderFrame(model, 0, -1, -1, -1, -1);
    }

    @Test
    public void joinTrueMeansNoFrameStillProcessing() throws Exception {
        List<Long> rendered = new CopyOnWriteArrayList<>();
        CountDownLatch backendEntered = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        ExecutorService executor = FakeAsyncFrameConsumer.newSerialBackendExecutor();
        try {
            FakeAsyncFrameConsumer consumer = new FakeAsyncFrameConsumer(executor, rev -> {
                backendEntered.countDown();
                try {
                    assertTrue(releaseBackend.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                rendered.add(rev);
            });
            consumer.attach(5L, new RenderGeometry(8, 4, 100, 100));

            TerminalRenderFrame f = frame(1);
            consumer.submit(f, RenderDamage.compute(f, null), null, 5L);
            assertTrue(backendEntered.await(5, TimeUnit.SECONDS));

            final AtomicBoolean joinResult = new AtomicBoolean(false);
            final CountDownLatch joinDone = new CountDownLatch(1);
            Thread joiner = new Thread(() -> {
                joinResult.set(consumer.detachAndJoin(5L, 30_000L));
                joinDone.countDown();
            });
            joiner.start();

            // Deterministic negative-direction assertion: a broken immediate-return
            // "join" (the default detach()+true placeholder) fails right here.
            assertFalse("join must block while a frame is in flight",
                joinDone.await(150, TimeUnit.MILLISECONDS));

            releaseBackend.countDown();
            assertTrue(joinDone.await(10, TimeUnit.SECONDS));
            assertTrue("completed drain yields true", joinResult.get());
            assertFalse("no frame still processing after true", consumer.isInFlight());
            assertEquals(1, rendered.size());
        } finally {
            releaseBackend.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void timeoutFalseIsRetryableAndTaskKeepsOwnership() throws Exception {
        List<Long> rendered = new CopyOnWriteArrayList<>();
        CountDownLatch backendEntered = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        ExecutorService executor = FakeAsyncFrameConsumer.newSerialBackendExecutor();
        try {
            FakeAsyncFrameConsumer consumer = new FakeAsyncFrameConsumer(executor, rev -> {
                backendEntered.countDown();
                try {
                    assertTrue(releaseBackend.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                rendered.add(rev);
            });
            consumer.attach(6L, new RenderGeometry(8, 4, 100, 100));

            TerminalRenderFrame f = frame(2);
            consumer.submit(f, RenderDamage.compute(f, null), null, 6L);
            assertTrue(backendEntered.await(5, TimeUnit.SECONDS));

            long startNanos = System.nanoTime();
            assertFalse("blocked backend must produce false within timeout",
                consumer.detachAndJoin(6L, 250L));
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue("timeout honored (waited " + waitedMs + "ms)", waitedMs >= 200 && waitedMs < 5_000);
            assertTrue("task still owned by consumer between false and retry", consumer.isInFlight());

            releaseBackend.countDown();
            assertTrue("retry after false must succeed",
                consumer.detachAndJoin(6L, 10_000L));
            assertEquals("the drained frame completed exactly once", 1, rendered.size());
        } finally {
            releaseBackend.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void afterTrueJoinBackendReceivesNothingMore() throws Exception {
        List<Long> rendered = new CopyOnWriteArrayList<>();
        ExecutorService executor = FakeAsyncFrameConsumer.newSerialBackendExecutor();
        try {
            FakeAsyncFrameConsumer consumer = new FakeAsyncFrameConsumer(executor, rendered::add);
            consumer.attach(7L, new RenderGeometry(8, 4, 100, 100));

            TerminalRenderFrame f = frame(3);
            consumer.submit(f, RenderDamage.compute(f, null), null, 7L);
            assertTrue(consumer.detachAndJoin(7L, 10_000L));

            // Producer keeps producing into a NEW generation after the join:
            // submits to the drained consumer's generation must be ignored.
            TerminalRenderFrame late = frame(4);
            consumer.attach(8L, new RenderGeometry(8, 4, 100, 100));
            consumer.submit(late, RenderDamage.compute(late, null), null, 7L); // stale generation
            Thread.sleep(100);
            assertEquals("stale-generation submit after true join renders nothing", 1, rendered.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void foreignGenerationSubmitIgnoredWithoutTouchingBackend() {
        List<Long> rendered = new CopyOnWriteArrayList<>();
        ExecutorService executor = FakeAsyncFrameConsumer.newSerialBackendExecutor();
        try {
            FakeAsyncFrameConsumer consumer = new FakeAsyncFrameConsumer(executor, rendered::add);
            consumer.attach(9L, new RenderGeometry(8, 4, 100, 100));

            TerminalRenderFrame f = frame(5);
            consumer.submit(f, RenderDamage.compute(f, null), null, 42L); // foreign generation

            assertTrue(consumer.detachAndJoin(9L, 1_000L));
            assertEquals("foreign-generation frame never reached the backend", 0, rendered.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void lateMailboxAcksForDrainedFramesStillSucceedAfterJoin() throws Exception {
        // The mailbox owns the ack ladder; detaching a consumer must not strand a
        // frame's RASTERED/SUBMITTED stages. Simulate the TerminalView.onDraw pattern:
        // submit on the consumer, then record acks after the drain completed in a join.
        ExecutorService executor = FakeAsyncFrameConsumer.newSerialBackendExecutor();
        try {
            FakeAsyncFrameConsumer consumer = new FakeAsyncFrameConsumer(executor, rev -> { });
            consumer.attach(10L, new RenderGeometry(8, 4, 100, 100));

            TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox =
                new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 10L, 10L);
            TerminalRenderFrame f = frame(6);
            TerminalFrameIdentity identity = new TerminalFrameIdentity(10L, 10L, f.getScreenRevision(), 1L);
            assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED, mailbox.submit(f, identity));

            consumer.submit(f, RenderDamage.compute(f, null), identity, 10L);
            assertTrue(consumer.detachAndJoin(10L, 10_000L));

            // Acks recorded AFTER the join — the frame rastered during it:
            assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
                mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.RASTERED));
            assertEquals(TerminalFrameConsumerMailbox.AckResult.RECORDED,
                mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.SUBMITTED));
            // PRESENTED stays rejected without physical evidence, even post-join:
            assertEquals(TerminalFrameConsumerMailbox.AckResult.REJECTED_ORDER,
                mailbox.recordAck(identity, TerminalFrameConsumerMailbox.AckStage.PRESENTED));
        } finally {
            executor.shutdownNow();
        }
    }
}
