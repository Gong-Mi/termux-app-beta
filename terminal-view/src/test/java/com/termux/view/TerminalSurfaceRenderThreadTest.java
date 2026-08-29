package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.FrameRevision;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Conformance for #52 spike 3a: the surface render THREAD shell. Pure-JVM command
 * loop owning the {@link TerminalSurfaceGenerationGate} + full-frame sequencer,
 * with pixel mechanics injected as {@link TerminalSurfaceRenderThread.Backbuffer}
 * (real Bitmap/Surface implementation lands in the Android slice 3b).
 *
 * Contract under test:
 * - surfaceCreated/Changed/Destroyed drive epochs; draws only happen while a
 *   surface is live;
 * - surfaceDestroyed BLOCKS its caller until the loop can no longer touch the
 *   old surface (Android SurfaceView contract), serialized with in-flight draws;
 * - bursts of frame signals coalesce into at most one presented frame per quiesce
 *   (latest-wins end to end);
 * - shutdown terminates the loop and join succeeds.
 */
public class TerminalSurfaceRenderThreadTest {

    /** Fake pixel target recording everything; supports blocking a draw in flight. */
    private static final class FakeBackbuffer implements TerminalSurfaceRenderThread.Backbuffer {
        final List<Integer> resizedToWidths = new CopyOnWriteArrayList<>();
        final List<Long> drawnRevisions = new CopyOnWriteArrayList<>();
        final AtomicInteger presentOk = new AtomicInteger();
        volatile boolean sized = true;
        CountDownLatch blockDrawEntered;
        CountDownLatch blockDrawRelease;

        @Override public void resizeTo(int widthPx, int heightPx) {
            resizedToWidths.add(widthPx);
            sized = true;
        }

        @Override public boolean hasSize() {
            return sized;
        }

        @Override public void drawAll(TerminalRenderFrame frame) {
            if (blockDrawEntered != null) blockDrawEntered.countDown();
            CountDownLatch release = blockDrawRelease;
            if (release != null) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            drawnRevisions.add(frame.screenRevision);
        }

        @Override public boolean present() {
            return presentOk.incrementAndGet() > 0;
        }
    }

    private static final class FrameFactory {
        private final TerminalEmulator mEmulator = emulator();

        TerminalRenderFrame next(String text) {
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            mEmulator.append(b, b.length);
            int count = mEmulator.getScreen().getDirtyMutationCount();
            long[] bits = count == 0 ? null : mEmulator.getScreen().getAndClearDirtyRowBits();
            return new TerminalRenderFrame(
                new TerminalModelFrame(mEmulator, 0, bits, count, null), 0, 0, -1, -1);
        }
    }

    private static TerminalEmulator emulator() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        return new TerminalEmulator(output, 8, 4, 13, 15, 8, null);
    }

    private static TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox() {
        return new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
    }

    private static void submit(TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox,
                               TerminalRenderFrame frame) {
        submit(mbox, frame, 7L);
    }

    private static void submit(TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox,
                               TerminalRenderFrame frame, long sessionGeneration) {
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED, mbox.submit(frame,
            new TerminalFrameIdentity(sessionGeneration, 3L,
                frame.screenRevision, frame.screenRevision)));
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    @Test
    public void lifecycleDrivesEpochsAndOnlyLiveSurfacesDraw() throws Exception {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox = mailbox();
        FakeBackbuffer bb = new FakeBackbuffer();
        TerminalSurfaceRenderThread thread =
            new TerminalSurfaceRenderThread("t", mbox, bb);
        thread.start();

        // Before any surface: frames are dropped untouched.
        FrameFactory f = new FrameFactory();
        submit(mbox, f.next("EARLY"));
        thread.requestCycle();
        assertTrue(await(() -> mbox.peekLatest() == null));
        assertTrue("no surface -> zero draws", bb.drawnRevisions.isEmpty());

        // Create + change: draws happen with the new geometry.
        thread.onSurfaceCreated();
        thread.onSurfaceChanged(320, 240);
        submit(mbox, f.next("LIVE"));
        thread.requestCycle();
        assertTrue(await(() -> !bb.drawnRevisions.isEmpty()));
        assertTrue(bb.resizedToWidths.contains(320));

        // Destroy stops all pixel traffic deterministically.
        assertTrue(thread.onSurfaceBlockedDestroy());
        int drawsAtDestroy = bb.drawnRevisions.size();
        submit(mbox, f.next("GHOST"));
        thread.requestCycle();
        Thread.sleep(120);
        assertEquals(drawsAtDestroy, bb.drawnRevisions.size());

        assertTrue(thread.shutdownAndJoin(5_000L));
    }

    @Test
    public void blockedDestroyReturnsOnlyAfterInFlightDrawFinishes() throws Exception {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox = mailbox();
        FakeBackbuffer bb = new FakeBackbuffer();
        bb.blockDrawEntered = new CountDownLatch(1);
        bb.blockDrawRelease = new CountDownLatch(1);
        TerminalSurfaceRenderThread thread =
            new TerminalSurfaceRenderThread("t", mbox, bb);
        thread.start();
        thread.onSurfaceCreated();

        submit(mbox, mailboxFrame());
        thread.requestCycle();
        assertTrue(bb.blockDrawEntered.await(5, TimeUnit.SECONDS));

        // Destroy from another thread must wait for the held draw.
        final CountDownLatch destroyReturned = new CountDownLatch(1);
        final boolean[] result = {false};
        Thread destroyer = new Thread(() -> {
            result[0] = thread.onSurfaceBlockedDestroy();
            destroyReturned.countDown();
        });
        destroyer.start();
        assertFalse("destroy must still be waiting on the in-flight draw",
            destroyReturned.await(150, TimeUnit.MILLISECONDS));

        bb.blockDrawRelease.countDown();
        assertTrue(destroyReturned.await(5, TimeUnit.SECONDS));
        assertTrue(result[0]);
        assertEquals("frame whose draw began before destroy completes",
            1, bb.drawnRevisions.size());

        assertTrue(thread.shutdownAndJoin(5_000L));
    }

    @Test
    public void signalBurstCoalescesToOnePresent() throws Exception {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox = mailbox();
        FakeBackbuffer bb = new FakeBackbuffer();
        TerminalSurfaceRenderThread thread =
            new TerminalSurfaceRenderThread("t", mbox, bb);
        thread.start();
        thread.onSurfaceCreated();

        FrameFactory f = new FrameFactory();
        submit(mbox, f.next("ONE"));
        submit(mbox, f.next("TWO"));
        for (int i = 0; i < 5; i++) thread.requestCycle();

        assertTrue(await(() -> !bb.drawnRevisions.isEmpty()));
        // Quiesce: exactly one cycle served the whole burst (latest frame wins).
        Thread.sleep(150);
        int draws = bb.drawnRevisions.size();
        Thread.sleep(120);
        assertEquals("burst must serve one full-frame present", 1, draws);

        assertTrue(thread.shutdownAndJoin(5_000L));
    }

    @Test
    public void shutdownJoinsEvenWithoutSurface() throws Exception {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox = mailbox();
        TerminalSurfaceRenderThread thread =
            new TerminalSurfaceRenderThread("t", mbox, new FakeBackbuffer());
        thread.start();
        thread.requestCycle();
        assertTrue(thread.shutdownAndJoin(5_000L));
        assertFalse(thread.isAlive());
        // Post-shutdown signals are safe no-ops.
        thread.onSurfaceCreated();
        thread.requestCycle();
        assertTrue(thread.shutdownAndJoin(1_000L));
    }

    @Test
    public void rebindSwapsMailboxWithoutKillingLoop() throws Exception {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> oldBox = mailbox();
        FakeBackbuffer bb = new FakeBackbuffer();
        TerminalSurfaceRenderThread thread =
            new TerminalSurfaceRenderThread("t", oldBox, bb);
        thread.start();
        thread.onSurfaceCreated();
        thread.onSurfaceChanged(320, 240);

        // A frame in the OLD mailbox before rebind must never be drawn.
        FrameFactory f = new FrameFactory();
        submit(oldBox, f.next("STALE-A"));
        assertTrue(thread.rebind(mailbox()));

        // New session's frames flow through the new mailbox.
        TerminalFrameConsumerMailbox<TerminalRenderFrame> newBox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 9L, 3L);
        assertTrue(thread.rebind(newBox));
        submit(newBox, f.next("FRESH"), 9L);
        thread.requestCycle();
        assertTrue(await(() -> !bb.drawnRevisions.isEmpty()));

        // Loop still alive: a second frame is also served after rebind.
        int drawsAfterFirst = bb.drawnRevisions.size();
        submit(newBox, f.next("SECOND"), 9L);
        thread.requestCycle();
        assertTrue(await(() -> bb.drawnRevisions.size() > drawsAfterFirst));

        assertTrue(thread.shutdownAndJoin(5_000L));
    }

    @Test
    public void rebindDropsUnservedOldMailboxFrame() throws Exception {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> oldBox = mailbox();
        FakeBackbuffer bb = new FakeBackbuffer();
        TerminalSurfaceRenderThread thread =
            new TerminalSurfaceRenderThread("t", oldBox, bb);
        thread.start();
        thread.onSurfaceCreated();

        // Old frame sits pending; no requestCycle, so it was never acquired.
        FrameFactory f = new FrameFactory();
        submit(oldBox, f.next("PENDING-OLD"));

        TerminalFrameConsumerMailbox<TerminalRenderFrame> newBox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 8L, 3L);
        assertTrue(thread.rebind(newBox));

        // Only the new mailbox's frame may appear on the backbuffer: exactly one
        // draw cycle served after rebind, and it came from the new mailbox.
        submit(newBox, f.next("NEW"), 8L);
        thread.requestCycle();
        assertTrue(await(() -> !bb.drawnRevisions.isEmpty()));
        Thread.sleep(100);
        assertEquals(1, bb.drawnRevisions.size());

        assertTrue(thread.shutdownAndJoin(5_000L));
    }

    @Test
    public void frameBeforeFirstResizeIsRetainedThenServedAfterResize() throws Exception {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox = mailbox();
        FakeBackbuffer bb = new FakeBackbuffer();
        TerminalSurfaceRenderThread thread =
            new TerminalSurfaceRenderThread("t", mbox, bb);
        thread.start();
        thread.onSurfaceCreated();

        // Simulate draw racing ahead of the first resize: unsized backbuffer.
        bb.sized = false;
        FrameFactory f = new FrameFactory();
        submit(mbox, f.next("EARLY"));
        thread.requestCycle();
        Thread.sleep(150);
        assertEquals("unsized backbuffer must not consume the frame", 0, bb.drawnRevisions.size());

        // First pixel size arrives (surfaceChanged): the queued frame is served.
        thread.onSurfaceChanged(320, 240);
        assertTrue(await(() -> !bb.drawnRevisions.isEmpty()));
        assertEquals("EARLY frame served after resize", 1, bb.drawnRevisions.size());

        assertTrue(thread.shutdownAndJoin(5_000L));
    }

    private static TerminalRenderFrame mailboxFrame() {
        FrameFactory f = new FrameFactory();
        return f.next("X");
    }
}
