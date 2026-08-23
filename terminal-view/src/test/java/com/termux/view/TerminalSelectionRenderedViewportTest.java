package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Regression contract for issue #39: gesture anchors, handle pixels and the ActionMode rect must
 * resolve against the viewport of the last <em>rendered</em> frame, not against the live scroll
 * target. Numbers in the "device" tests are taken verbatim from a real Android 17 memory dump
 * (pandora, parser-worker build): frame topRow=-2 while mTopRow (scroll target) was -3, font
 * metrics lineSpacing=37 / lineSpacingAndAscent=9, long-press at y=480, handle anchor y=333 px.
 */
public class TerminalSelectionRenderedViewportTest {

    @Test
    public void anchorUsesRenderedViewportNotScrollTarget() {
        // y=480 -> local row 12 (device metrics). Rendered viewport -2 -> external row 10.
        assertEquals(10, TerminalSelectionCoordinates.rowOfRenderedContent(480f, 9, 37, -3, -2, true));
    }

    @Test
    public void anchorIsIndependentOfScrollTargetWhileFrameLags() {
        // The scroll target was -7 when the long-press anchored, but the displayed frame was -2;
        // the anchor must describe the text the user actually saw (row 10), not the target row 5.
        assertEquals(10, TerminalSelectionCoordinates.rowOfRenderedContent(480f, 9, 37, -7, -2, true));
    }

    @Test
    public void anchorFallsBackToScrollTargetBeforeFirstFrame() {
        assertEquals(9, TerminalSelectionCoordinates.rowOfRenderedContent(480f, 9, 37, -3, -2, false));
        assertEquals(5, TerminalSelectionCoordinates.rowOfRenderedContent(480f, 9, 37, -7, -2, false));
    }

    @Test
    public void settledViewportKeepsLegacyMapping() {
        assertEquals(12, TerminalSelectionCoordinates.rowOfRenderedContent(480f, 9, 37, 0, 0, true));
        assertEquals(13, TerminalSelectionCoordinates.rowFromPixel(480f, 9, 37, 1));
    }

    @Test
    public void handlePixelSitsOnRenderedRow() {
        // Handle is anchored at external row cy+1 (TextSelectionHandleView.positionAtCursor).
        // Device: cy+1=7, rendered topRow -2 -> (7 - (-2)) * 37 = 333 px.
        assertEquals(333, TerminalSelectionCoordinates.pixelOfRenderedRow(7, 37, -3, -2, true));
        // Selection row itself (cy=6) -> 296 px = top of row 8.
        assertEquals(296, TerminalSelectionCoordinates.pixelOfRenderedRow(6, 37, -3, -2, true));
    }

    @Test
    public void handlePixelFallsBackToScrollTargetBeforeFirstFrame() {
        // Old behavior ((7 - (-3)) * 37 = 370) is retained only while no frame was rendered yet.
        assertEquals(370, TerminalSelectionCoordinates.pixelOfRenderedRow(7, 37, -3, -2, false));
    }

    @Test
    public void handlePixelMatchesAnchorRoundTrip() {
        // Row-to-pixel uses the legacy (cy - topRow) * lineSpacing convention while pixel-to-row
        // adds the baselineOffset (+9). A pixel 1px inside the row's text span round-trips; the
        // row's very first pixel belongs to the previous row by the floor convention.
        int rowSpanStart = 9 + TerminalSelectionCoordinates.pixelOfRenderedRow(6, 37, -3, -2, true) + 1;
        assertEquals(6, TerminalSelectionCoordinates.rowOfRenderedContent(rowSpanStart, 9, 37, -3, -2, true));
    }

    @Test
    public void rowOfRenderedContentRejectsNonPositiveLineSpacing() {
        try {
            TerminalSelectionCoordinates.rowOfRenderedContent(100f, 9, 0, 0, 0, true);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }

    // ---- clampSelectionRow: never read outside the captured snapshot window ----

    @Test
    public void dragIntoHistoryClampsToCapturedWindowNotTranscriptTop() {
        // Device: transcript 313 rows, captured window [-2, 57). Dragging to -300 must clamp to
        // the captured window instead of silently reading blank history text.
        assertEquals(-2, TerminalSelectionCoordinates.clampSelectionRow(-300, 313, -2, 57));
        assertEquals(-2, TerminalSelectionCoordinates.clampSelectionRow(-313, 313, -2, 57));
    }

    @Test
    public void dragBelowScreenClampsToCapturedWindow() {
        assertEquals(56, TerminalSelectionCoordinates.clampSelectionRow(100, 313, -2, 57));
        assertEquals(56, TerminalSelectionCoordinates.clampSelectionRow(56, 313, -2, 57));
    }

    @Test
    public void inWindowRowsPassThrough() {
        assertEquals(-1, TerminalSelectionCoordinates.clampSelectionRow(-1, 313, -2, 57));
        assertEquals(0, TerminalSelectionCoordinates.clampSelectionRow(0, 313, -2, 57));
        assertEquals(30, TerminalSelectionCoordinates.clampSelectionRow(30, 313, -2, 57));
    }

    @Test
    public void transcriptLimitWinsWhenWiderThanCapturedWindow() {
        // Scrolled to the very top: captured window starts at -3 == -transcript rows.
        assertEquals(-3, TerminalSelectionCoordinates.clampSelectionRow(-100, 3, -3, 32));
    }

    @Test
    public void emptyTranscriptClampsToCapturedWindow() {
        assertEquals(0, TerminalSelectionCoordinates.clampSelectionRow(-5, 0, 0, 32));
        assertEquals(31, TerminalSelectionCoordinates.clampSelectionRow(50, 0, 0, 32));
    }

    @Test
    public void degenerateWindowPinsToFirstRow() {
        assertEquals(5, TerminalSelectionCoordinates.clampSelectionRow(10, 100, 5, 5));
    }
}