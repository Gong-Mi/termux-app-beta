package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TerminalSelectionRangeTest {
    @Test
    public void selectorProtocolMapsY1Y2X1X2ToFrameX1Y1X2Y2() {
        int[] selectors = {5, 7, 3, 9};

        TerminalSelectionRange range = TerminalSelectionRange.fromSelectors(selectors);

        assertEquals(3, range.x1);
        assertEquals(5, range.y1);
        assertEquals(9, range.x2);
        assertEquals(7, range.y2);
    }

    @Test
    public void selectorProtocolPreservesUnsetSelection() {
        TerminalSelectionRange range = TerminalSelectionRange.fromSelectors(
            new int[]{-1, -1, -1, -1});

        assertEquals(-1, range.x1);
        assertEquals(-1, range.y1);
        assertEquals(-1, range.x2);
        assertEquals(-1, range.y2);
    }
}
