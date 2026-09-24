package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalScreenSnapshot;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Regression for the on-device crash (2026-09-24, 0.119.0-beta.3):
 *
 * <pre>
 * IllegalArgumentException: externalRow=47 outside [-1,47)
 *   at TerminalScreenSnapshot.rowAtExternal(TerminalScreenSnapshot.java:84)
 *   at TerminalRenderer.render(TerminalRenderer.java:165)
 * </pre>
 *
 * The view repackaged a worker frame captured at viewport topRow=-1 with its
 * own mTopRow=0, so the render loop walked [0,48) while the snapshot only
 * covered [-1,47). This file pins the projection-contract fixes at JVM level:
 *
 * 1. The 6-arg projection constructor must NOT silently accept a viewport
 *    that the model frame's snapshot does not cover. It either clamps to the
 *    model viewport (covered) or falls back to the model frame's own viewport.
 * 2. The static picker must never hand the renderer a mismatched pair.
 */
public class TerminalProjectionViewportMismatchTest {

    private static final class NoOpOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private static TerminalEmulator emulator(int columns, int rows) {
        return new TerminalEmulator(new NoOpOutput(), columns, rows, 13, 15, 8, null);
    }

    /** Model frame captured at topRow=-1 (one transcript row above the live screen). */
    private static TerminalModelFrame scrolledUpModel(TerminalEmulator emulator) {
        byte[] history = "line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8);
        emulator.append(history, history.length);
        return new TerminalModelFrame(emulator, -1, null, 0);
    }

    @Test
    public void projectionConstructorRejectsViewportMismatchByFallingBackToModelViewport() {
        TerminalEmulator emulator = emulator(8, 4);
        TerminalModelFrame model = scrolledUpModel(emulator);

        // View scrolled back to bottom (mTopRow=0) while the model frame was
        // captured at -1: the crash configuration. The projection must NOT
        // claim rows the snapshot does not own.
        TerminalRenderFrame projection = new TerminalRenderFrame(model, 0, -1, -1, -1, -1);

        assertEquals("projection must fall back to the model viewport when its own "
            + "viewport is not covered by the snapshot", -1, projection.topRow);
        assertSame(model.screen, projection.screen);
        for (int row = projection.topRow; row < projection.endRow; row++) {
            // Must not throw IllegalArgumentException like the on-device crash.
            projection.screen.rowAtExternal(row);
        }
    }

    @Test
    public void projectionConstructorKeepsViewViewportWhenSnapshotCoversIt() {
        TerminalEmulator emulator = emulator(8, 4);
        TerminalModelFrame model = scrolledUpModel(emulator);

        // Snapshot covers [-1,3); asking for viewport -1 is covered: kept.
        TerminalRenderFrame projection = new TerminalRenderFrame(model, -1, -1, -1, -1, -1);

        assertEquals(-1, projection.topRow);
        assertEquals(model.rows, projection.endRow - projection.topRow);
        assertSame(model.screen, projection.screen);
    }

    @Test
    public void pickerFallsBackToLastRenderedFrameWhenProjectionViewportMismatched() {
        TerminalEmulator emulator = emulator(8, 4);
        TerminalModelFrame model = scrolledUpModel(emulator);

        // Last rendered frame at model viewport (view already drew this range).
        TerminalRenderFrame lastRendered = new TerminalRenderFrame(model, -1, -1, -1, -1, -1);
        // A new model frame arrives whose snapshot does NOT cover the view's
        // current viewport: the picker must defer to the inbound one.
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 1L, 1L);
        TerminalRenderFrame projection = new TerminalRenderFrame(model, 0, -1, -1, -1, -1);
        TerminalFrameIdentity identity = new TerminalFrameIdentity(1L, 1L, projection.screenRevision, 1L);
        mailbox.submit(projection, identity);

        TerminalRenderFrame picked = TerminalView.frameForDraw(mailbox.acquireLatest(), lastRendered);
        assertSame("picker must draw the fallback-viewport projection of the newest model frame",
            projection, picked);
        assertEquals(-1, picked.topRow);
    }
}
