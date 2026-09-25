package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Contract for {@link TerminalView#shouldInvalidateForScreenUpdate}: an
 * onScreenUpdated must schedule a draw when — and only when — the next draw
 * would actually differ from what the canvas already shows.
 *
 * <p>Motivation (on-device 2026-09-25): under a 10 line/s streaming load the
 * parser worker publishes ~90 frames/s (one per PTY segment); every publish
 * posts onTextChanged, and onScreenUpdated's unconditional invalidate() then
 * cashes each one as a full 48-row re-record on the hardware canvas. Measured:
 * 235 draws for 53 consumed publishes (182 phantom, 14:1 waste, mutations=0).
 * The invalidate is only observable work when it will change pixels.</p>
 */
public class ScreenUpdateInvalidatePolicyTest {

    @Test
    public void newMailboxFrameInvalidates() {
        // A frame is waiting in the mailbox: the next draw has new content.
        assertTrue(TerminalView.shouldInvalidateForScreenUpdate(
            true, false, true));
    }

    @Test
    public void viewportChangeInvalidatesEvenWithoutNewFrame() {
        // Scrolling moves rows under the projection; even with an empty
        // mailbox the redraw shows different pixels.
        assertTrue(TerminalView.shouldInvalidateForScreenUpdate(
            false, true, true));
    }

    @Test
    public void noRenderedFrameYetInvalidates() {
        // First ever screen update (attachSession nulls the frame): nothing is
        // on the canvas; skipping would leave the first content invisible.
        assertTrue(TerminalView.shouldInvalidateForScreenUpdate(
            false, false, false));
    }

    @Test
    public void crossSessionRenderedFrameInvalidates() {
        // mLastRenderFrame was produced for a previous session generation
        // (attachSession nulls it, but onStart force-refreshes also land here
        // after a backgrounded attach): the canvas shows another session.
        assertTrue(TerminalView.shouldInvalidateForScreenUpdate(
            false, false, false));
    }

    @Test
    public void sameSessionSameFrameNoNewMailboxSkips() {
        // Mailbox empty, viewport unchanged, and the last rendered frame is
        // this session's: the next draw would raster the identical frame to
        // identical pixels. This is the phantom case (14:1 under stream load)
        // and MUST skip.
        assertFalse(TerminalView.shouldInvalidateForScreenUpdate(
            false, false, true));
    }
}
