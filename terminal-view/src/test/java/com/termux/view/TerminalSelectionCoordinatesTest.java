package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TerminalSelectionCoordinatesTest {
    @Test
    public void pixelToRowUsesTheRendererBaseline() {
        assertEquals(0, TerminalSelectionCoordinates.rowFromPixel(52f, 52, 20, 0));
        assertEquals(1, TerminalSelectionCoordinates.rowFromPixel(72f, 52, 20, 0));
    }

    @Test
    public void pixelAboveBaselineMapsToPreviousRowInsteadOfZero() {
        assertEquals(-1, TerminalSelectionCoordinates.rowFromPixel(51f, 52, 20, 0));
    }

    @Test
    public void pixelToRowPreservesTranscriptViewportOffset() {
        assertEquals(-3, TerminalSelectionCoordinates.rowFromPixel(52f, 52, 20, -3));
        assertEquals(-2, TerminalSelectionCoordinates.rowFromPixel(72f, 52, 20, -3));
    }
}
