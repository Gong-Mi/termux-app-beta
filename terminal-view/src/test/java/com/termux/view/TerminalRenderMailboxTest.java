package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class TerminalRenderMailboxTest {

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

    private static TerminalRenderFrame frame(TerminalEmulator emulator, long expectRevision) {
        byte[] input = "A".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        return new TerminalRenderFrame(emulator, 0,
            emulator.getScreen().getAndClearDirtyRowBits(),
            emulator.getScreen().getDirtyMutationCount(), 0, 0, -1, -1);
    }

    @Test
    public void acquireReturnsNullWhenEmpty() {
        RenderFrameMetrics metrics = new RenderFrameMetrics();
        TerminalRenderMailbox<TerminalRenderFrame> mailbox = new TerminalRenderMailbox<>(metrics);
        assertNull(mailbox.acquireLatest());
        assertEquals(0, metrics.getPublishedFrameCount());
        assertEquals(0, metrics.getDrawnFrameCount());
        assertEquals(0, metrics.getDroppedFrameCount());
    }

    @Test
    public void publishMakesFrameAvailableAndCountsPublished() {
        TerminalEmulator emulator = emulator();
        TerminalRenderFrame f = frame(emulator, 1L);

        RenderFrameMetrics metrics = new RenderFrameMetrics();
        TerminalRenderMailbox<TerminalRenderFrame> mailbox = new TerminalRenderMailbox<>(metrics);
        mailbox.publish(f);

        assertEquals(1, metrics.getPublishedFrameCount());
        assertEquals(0, metrics.getDroppedFrameCount());
        assertSame(f, mailbox.acquireLatest());
        assertNull(mailbox.acquireLatest());
    }

    @Test
    public void unpublishedFrameIsDroppedWhenReplaced() {
        TerminalEmulator emulator = emulator();
        TerminalRenderFrame first = frame(emulator, 1L);
        TerminalRenderFrame second = frame(emulator, 2L);

        RenderFrameMetrics metrics = new RenderFrameMetrics();
        TerminalRenderMailbox<TerminalRenderFrame> mailbox = new TerminalRenderMailbox<>(metrics);
        mailbox.publish(first);
        mailbox.publish(second);

        assertEquals(2, metrics.getPublishedFrameCount());
        assertEquals(1, metrics.getDroppedFrameCount());
        assertSame(second, mailbox.acquireLatest());
        assertNull(mailbox.acquireLatest());
    }

    @Test
    public void multipleUnrenderedFramesAreDropped() {
        TerminalEmulator emulator = emulator();
        RenderFrameMetrics metrics = new RenderFrameMetrics();
        TerminalRenderMailbox<TerminalRenderFrame> mailbox = new TerminalRenderMailbox<>(metrics);

        TerminalRenderFrame last = null;
        for (int i = 0; i < 5; i++) {
            last = frame(emulator, i + 1);
            mailbox.publish(last);
        }

        assertEquals(5, metrics.getPublishedFrameCount());
        assertEquals(4, metrics.getDroppedFrameCount());
        assertSame(last, mailbox.acquireLatest());
        assertNull(mailbox.acquireLatest());
    }

    @Test
    public void coalescedRevisionCountTracksSkippedRevisions() {
        TerminalEmulator emulator = emulator();
        RenderFrameMetrics metrics = new RenderFrameMetrics();
        TerminalRenderMailbox<TerminalRenderFrame> mailbox = new TerminalRenderMailbox<>(metrics);

        // Publish and ack the first frame.
        TerminalRenderFrame f1 = frame(emulator, 1L);
        mailbox.publish(f1);
        metrics.ack(f1.screenRevision);

        // Generate three more revisions but only publish one frame for them.
        frame(emulator, 2L);
        frame(emulator, 3L);
        TerminalRenderFrame f3 = frame(emulator, 4L);
        mailbox.publish(f3);

        assertEquals(2, metrics.getPublishedFrameCount());
        assertEquals(2, metrics.getCoalescedRevisionCount());
    }
}
