package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.FrameRevision;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conformance for #52 spike step 3 ("先只做 full-frame backbuffer"): the
 * sequencer owns the render thread's decision core — acquire latest frame,
 * validate against the LIVE surface epoch, rasterize EVERYTHING (no incremental
 * reuse at this stage), present once, and advance the presented marker only on
 * confirmed presentation. Pixel work sits behind {@link TerminalBackbufferSequencer.Ops}
 * so the whole protocol stays JVM-verifiable before the Android shell exists.
 */
public class TerminalBackbufferSequencerTest {

    private static final class TestFrame implements FrameRevision {
        private final long revision;
        TestFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    /** Recording Ops: asserts sequencing promises, counts blits. */
    private static final class RecordingOps implements TerminalBackbufferSequencer.Ops {
        final List<Long> drawnFrames = new ArrayList<>();
        final List<int[]> drawnRowRanges = new ArrayList<>();
        int resizedAtWidth = -1;
        int presentCalls;
        boolean surfaceAlive = true;
        int width = 8;

        @Override public void resizeIfNeeded(int width, int height) {
            if (width != this.width) {
                this.width = width;
            }
            resizedAtWidth = width;
        }

        @Override public void drawAll(TerminalRenderFrame frame) {
            drawnFrames.add(frame.screenRevision);
            drawnRowRanges.add(new int[]{frame.topRow, frame.endRow});
        }

        @Override public boolean present() {
            presentCalls++;
            return surfaceAlive;
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

    private static void feed(TerminalEmulator em, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        em.append(b, b.length);
    }

    private static TerminalRenderFrame capture(TerminalEmulator em) {
        int count = em.getScreen().getDirtyMutationCount();
        long[] bits = count == 0 ? null : em.getScreen().getAndClearDirtyRowBits();
        return new TerminalRenderFrame(new TerminalModelFrame(em, 0, bits, count, null), 0, 0, -1, -1);
    }

    private static void submit(TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox,
                               TerminalRenderFrame frame, long session, long target, long projection) {
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED, mbox.submit(frame,
            new TerminalFrameIdentity(session, target, frame.screenRevision, projection)));
    }

    @Test
    public void idleWhenNoFramePending() {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingOps ops = new RecordingOps();
        AtomicLong epoch = new AtomicLong(5L);
        TerminalBackbufferSequencer seq =
            new TerminalBackbufferSequencer(mbox, epoch::get, ops);

        assertEquals(TerminalBackbufferSequencer.StepResult.IDLE, seq.step());
        assertEquals(0, ops.presentCalls);
        assertTrue(ops.drawnFrames.isEmpty());
    }

    @Test
    public void presentsFullFrameWithWholeRowRange() {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingOps ops = new RecordingOps();
        AtomicLong epoch = new AtomicLong(5L);
        TerminalBackbufferSequencer seq =
            new TerminalBackbufferSequencer(mbox, epoch::get, ops);

        feed(emulator(), "");
        submit(mbox, capture(emulator()), 7L, 3L, 1L);
        assertEquals(TerminalBackbufferSequencer.StepResult.PRESENTED, seq.step());

        assertEquals(1, ops.drawnFrames.size());
        assertEquals(1, ops.presentCalls);
        int[] range = ops.drawnRowRanges.get(0);
        assertEquals("full-frame policy: raster starts at viewport top", 0, range[0]);
        assertEquals("full-frame policy: raster ends at viewport end", 4, range[1]);
        assertNull("slot drained by the step", mbox.peekLatest());
        assertEquals(7L, seq.lastPresentedSessionGeneration());
        assertEquals(1L, seq.lastPresentedProjectionRevision());
    }

    @Test
    public void coalescedFramesPresentOnceAsLatest() {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingOps ops = new RecordingOps();
        TerminalBackbufferSequencer seq =
            new TerminalBackbufferSequencer(mbox, () -> 5L, ops);

        TerminalEmulator em = emulator();
        feed(em, "ONE");
        TerminalRenderFrame f1 = capture(em);
        submit(mbox, f1, 7L, 3L, 1L);
        feed(em, " TWO");
        TerminalRenderFrame f2 = capture(em);
        submit(mbox, f2, 7L, 3L, 2L);

        assertEquals(TerminalBackbufferSequencer.StepResult.PRESENTED, seq.step());
        assertEquals("intermediate frame must be skipped entirely", 1, ops.drawnFrames.size());
        assertEquals("latest frame wins", f2.screenRevision, ops.drawnFrames.get(0).longValue());
    }

    @Test
    public void noSurfaceSkipsWithoutTouchingPixels() {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingOps ops = new RecordingOps();
        AtomicLong epoch = new AtomicLong(0L); // destroyed
        TerminalBackbufferSequencer seq =
            new TerminalBackbufferSequencer(mbox, epoch::get, ops);

        TerminalEmulator em = emulator();
        feed(em, "X");
        submit(mbox, capture(em), 7L, 3L, 1L);

        assertEquals(TerminalBackbufferSequencer.StepResult.SKIPPED_NO_SURFACE, seq.step());
        assertEquals(0, ops.drawnFrames.size());
        assertEquals(0, ops.presentCalls);
        assertNull("skipped-for-no-surface frame drops out of the slot", mbox.peekLatest());
    }

    @Test
    public void epochChangeStillRasterizesFullFrameButMarksGeometryFresh() {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingOps ops = new RecordingOps();
        AtomicLong epoch = new AtomicLong(5L);
        TerminalBackbufferSequencer seq =
            new TerminalBackbufferSequencer(mbox, epoch::get, ops);

        TerminalEmulator em = emulator();
        feed(em, "A");
        submit(mbox, capture(em), 7L, 3L, 1L);
        assertEquals(TerminalBackbufferSequencer.StepResult.PRESENTED, seq.step());
        assertTrue("first-ever present initializes the backbuffer: geometry refresh",
            seq.lastEpochWasGeometryChange());

        feed(em, "B");
        submit(mbox, capture(em), 7L, 3L, 2L);
        assertEquals(TerminalBackbufferSequencer.StepResult.PRESENTED, seq.step());
        assertFalse("same epoch, no resize: plain repaint", seq.lastEpochWasGeometryChange());

        epoch.set(6L); // surfaceChanged resized the surface
        feed(em, "C");
        submit(mbox, capture(em), 7L, 3L, 3L);
        assertEquals(TerminalBackbufferSequencer.StepResult.PRESENTED, seq.step());
        assertTrue("post-resize frame is a geometry refresh", seq.lastEpochWasGeometryChange());
        assertEquals(3, ops.drawnFrames.size());
    }

    @Test
    public void lostSurfaceAtPresentKeepsMarkerAndRetriesAfterRecreate() {
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        RecordingOps ops = new RecordingOps();
        AtomicLong epoch = new AtomicLong(5L);
        TerminalBackbufferSequencer seq =
            new TerminalBackbufferSequencer(mbox, epoch::get, ops);

        TerminalEmulator em = emulator();
        feed(em, "A");
        submit(mbox, capture(em), 7L, 3L, 1L);
        ops.surfaceAlive = false;
        assertEquals(TerminalBackbufferSequencer.StepResult.SURFACE_LOST, seq.step());
        assertEquals("presented marker must not advance on lost surface",
            -1L, seq.lastPresentedSessionGeneration());

        // New surface era: epoch bumped, surface healthy again. Next frame draws and presents.
        epoch.set(9L);
        ops.surfaceAlive = true;
        feed(em, "AB");
        submit(mbox, capture(em), 7L, 3L, 2L);
        assertEquals(TerminalBackbufferSequencer.StepResult.PRESENTED, seq.step());
        assertEquals(Long.valueOf(7L), Long.valueOf(seq.lastPresentedSessionGeneration()));
        assertEquals("both frames rasterized (first attempt drew, marker just didn't advance)",
            2, ops.drawnFrames.size());
    }
}
