package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerminalRenderTargetGeometryTest {

    @Test
    public void pixelResizeRebindsEvenWhenCellGeometryIsUnchanged() {
        assertTrue(TerminalView.renderTargetGeometryChanged(1080, 1920, 1200, 1920));
    }

    @Test
    public void unchangedPixelGeometryDoesNotCreateAnotherTargetGeneration() {
        assertFalse(TerminalView.renderTargetGeometryChanged(1080, 1920, 1080, 1920));
    }

    @Test
    public void zeroSizedInitialLayoutDoesNotRebind() {
        assertFalse(TerminalView.renderTargetGeometryChanged(0, 0, 0, 0));
    }
}
