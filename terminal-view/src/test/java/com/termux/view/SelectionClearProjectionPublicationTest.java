package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalScreenSnapshot;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Issue #57 projection-publication contract: every View-only selection transition,
 * including selection CLEAR, must supersede the last published projection frame.
 *
 * <p>Regression scenario: the user clears an active selection. The controller's
 * {@code hide()} resets selectors to -1 and deactivates, but pre-fix
 * {@code stopTextSelectionMode()} only invalidated — the mailbox stayed empty and
 * {@code onDraw} re-rendered {@code mLastRenderFrame}, which still carried the
 * cleared selection's highlight. The highlight then persisted until an unrelated
 * model frame happened to arrive.</p>
 */
public class SelectionClearProjectionPublicationTest {

    private static final class NoOpOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    /** Frame published while a selection (y1=2,y2=4,x1=1,x2=6) was active. */
    private static TerminalRenderFrame frameWithActiveSelection() {
        TerminalEmulator emulator = new TerminalEmulator(new NoOpOutput(), 8, 4, 13, 15, 8, null);
        byte[] input = "sel".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        TerminalModelFrame model = new TerminalModelFrame(emulator, 0, null, 0);
        // Selector protocol is [y1, y2, x1, x2].
        return new TerminalRenderFrame(model, 0, 1, 2, 6, 4);
    }

    @Test
    public void clearedSelectorsMustCountAsChangedAgainstHighlightedFrame() {
        TerminalRenderFrame highlighted = frameWithActiveSelection();
        // Controller hide() resets selectors to -1.
        int[] cleared = {-1, -1, -1, -1};
        assertTrue("clearing a highlighted selection is a projection change and must republish",
            TerminalView.selectionChangedFromLastPublished(cleared, highlighted));
    }

    @Test
    public void secondClearAfterClearPublicationIsCorrectlyDeduplicated() {
        // After the clear has been published, the frame carries the cleared (-1) selection.
        TerminalEmulator emulator = new TerminalEmulator(new NoOpOutput(), 8, 4, 13, 15, 8, null);
        byte[] input = "sel".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        TerminalModelFrame model = new TerminalModelFrame(emulator, 0, null, 0);
        TerminalRenderFrame clearedFrame = new TerminalRenderFrame(model, 0, -1, -1, -1, -1);
        int[] cleared = {-1, -1, -1, -1};
        assertFalse("a repeated clear against an already-clear published frame is a no-op",
            TerminalView.selectionChangedFromLastPublished(cleared, clearedFrame));
    }

    @Test
    public void sameSelectionAgainstItsOwnFrameStaysDeduplicated() {
        TerminalRenderFrame highlighted = frameWithActiveSelection();
        // Selector protocol [y1, y2, x1, x2] = [2, 4, 1, 6] matches frame (x1=1,y1=2,x2=6,y2=4).
        int[] same = {2, 4, 1, 6};
        assertFalse(TerminalView.selectionChangedFromLastPublished(same, highlighted));
    }

    @Test
    public void movedSelectionMustRepublish() {
        TerminalRenderFrame highlighted = frameWithActiveSelection();
        int[] moved = {2, 5, 1, 6};
        assertTrue(TerminalView.selectionChangedFromLastPublished(moved, highlighted));
    }

    @Test
    public void nullSelectorsOrFrameMustRepublish() {
        TerminalRenderFrame highlighted = frameWithActiveSelection();
        assertTrue(TerminalView.selectionChangedFromLastPublished(null, highlighted));
        assertTrue(TerminalView.selectionChangedFromLastPublished(new int[3], highlighted));
        assertTrue(TerminalView.selectionChangedFromLastPublished(new int[]{2, 4, 1, 6}, null));
    }
}
