package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Projection-viewport invariant for {@link TerminalRenderFrame}'s
 * model-backed constructor.
 *
 * <p>On-device crash (0.119.0-beta.3 / versionCode 1022, chain head 9ab0b49,
 * 2026-09-25; same defect previously seen 2026-09-24 19:04/19:06):</p>
 *
 * <pre>
 * IllegalArgumentException: externalRow=-1 outside [0,41)   (00:28:05, PID 21093)
 * IllegalArgumentException: externalRow=47 outside [-1,47)   (00:33:44, PID 24313)
 *   at TerminalScreenSnapshot.rowAtExternal(TerminalScreenSnapshot.java:84)
 *   at TerminalRenderer.render(TerminalRenderer.java:165)
 *   at CanvasFrameConsumer.submit(CanvasFrameConsumer.java:68)
 * </pre>
 *
 * <p>The projection packages the newest model frame's snapshot — which only
 * covers [model.topRow, model.endRow) — with the view's current mTopRow.
 * Whenever the view viewport has drifted from the worker viewport captured in
 * the model frame (selection auto-anchor, scroll-to-bottom, IME resize), the
 * render loop walks a row range the snapshot does not own and the boundary
 * check throws on the main thread.</p>
 *
 * <p>These cases pin the contract: a render frame may never claim a row range
 * its snapshot cannot answer for, in BOTH drift directions, and a covered
 * viewport must be kept.</p>
 */
public class TerminalProjectionViewportInvariantTest {

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

    private static void appendHistory(TerminalEmulator emulator) {
        byte[] history = "line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8);
        emulator.append(history, history.length);
    }

    /** Model frame captured at topRow=-1 (one transcript row above the live screen). */
    private static TerminalModelFrame scrolledUpModel(TerminalEmulator emulator) {
        appendHistory(emulator);
        return new TerminalModelFrame(emulator, -1, null, 0);
    }

    /** Assert the frame's claimed row window is fully owned by its snapshot. */
    private static void assertWindowCoveredBySnapshot(TerminalRenderFrame frame) {
        // Walk first: the contract violation must surface as the same
        // IllegalArgumentException the renderer threw on-device.
        for (int row = frame.topRow; row < frame.endRow; row++) {
            frame.screen.rowAtExternal(row);
        }
        assertEquals("frame window must start at the snapshot's first external row",
            frame.screen.firstExternalRow(), frame.topRow);
        assertEquals("frame window must end at the snapshot's end external row",
            frame.screen.endExternalRow(), frame.endRow);
    }

    @Test
    public void viewBelowModelFallsBackToModelViewport() {
        // Crash B direction: model captured at -1, view scrolled back to 0.
        TerminalEmulator emulator = emulator(8, 4);
        TerminalModelFrame model = scrolledUpModel(emulator);

        TerminalRenderFrame projection = new TerminalRenderFrame(model, 0, -1, -1, -1, -1);

        assertEquals("projection must fall back to the model viewport", -1, projection.topRow);
        assertSame(model.screen, projection.screen);
        assertWindowCoveredBySnapshot(projection);
    }

    @Test
    public void viewAboveModelFallsBackToModelViewport() {
        // Crash A direction: model captured at 0 (post-IME-resize window), view drifted to -1.
        TerminalEmulator emulator = emulator(8, 4);
        appendHistory(emulator);
        TerminalModelFrame model = new TerminalModelFrame(emulator, 0, null, 0);

        TerminalRenderFrame projection = new TerminalRenderFrame(model, -1, -1, -1, -1, -1);

        assertEquals("projection must fall back to the model viewport", 0, projection.topRow);
        assertSame(model.screen, projection.screen);
        assertWindowCoveredBySnapshot(projection);
    }

    @Test
    public void coveredViewportIsKept() {
        TerminalEmulator emulator = emulator(8, 4);
        TerminalModelFrame model = scrolledUpModel(emulator);

        TerminalRenderFrame projection = new TerminalRenderFrame(model, -1, -1, -1, -1, -1);

        assertEquals(-1, projection.topRow);
        assertEquals(model.rows, projection.endRow - projection.topRow);
        assertSame(model.screen, projection.screen);
        assertWindowCoveredBySnapshot(projection);
    }

    @Test
    public void windowCoverageHoldsForEveryViewportDrift() {
        TerminalEmulator emulator = emulator(8, 4);
        TerminalModelFrame model = scrolledUpModel(emulator);

        for (int drift = -4; drift <= 2; drift++) {
            TerminalRenderFrame projection = new TerminalRenderFrame(model, drift, -1, -1, -1, -1);
            assertWindowCoveredBySnapshot(projection);
        }
    }

    @Test
    public void pickerDrawsInboundProjectionWhenViewportMismatched() {
        TerminalEmulator emulator = emulator(8, 4);
        TerminalModelFrame model = scrolledUpModel(emulator);

        TerminalRenderFrame lastRendered = new TerminalRenderFrame(model, -1, -1, -1, -1, -1);
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
