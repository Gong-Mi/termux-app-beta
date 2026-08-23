package com.termux.view;

import junit.framework.TestCase;

public class TerminalRenderStepMetricsTest extends TestCase {

    public void testDeltaDrainsAndResets() {
        TerminalRenderStepMetrics metrics = new TerminalRenderStepMetrics();
        metrics.recordVisitedRow();
        metrics.recordVisitedCell();
        metrics.recordDrawTextRunCall();
        metrics.recordDrawRectCall();

        TerminalRenderStepMetrics.Snapshot first = metrics.getAndResetDelta();
        assertEquals(1, first.visitedRows);
        assertEquals(1, first.visitedCells);
        assertEquals(1, first.drawTextRunCalls);
        assertEquals(1, first.drawRectCalls);

        TerminalRenderStepMetrics.Snapshot second = metrics.getAndResetDelta();
        assertEquals(0, second.visitedRows);
        assertEquals(0, second.visitedCells);
        assertEquals(0, second.drawTextRunCalls);
        assertEquals(0, second.drawRectCalls);
    }

    public void testAllCountersAccumulateIndependently() {
        TerminalRenderStepMetrics metrics = new TerminalRenderStepMetrics();
        metrics.recordSkippedRow();
        metrics.recordWcWidthCall();
        metrics.recordGlyphMeasureCall();
        metrics.recordVisitedRow();
        metrics.recordVisitedRow();
        metrics.recordVisitedCell();
        metrics.recordVisitedCell();
        metrics.recordVisitedCell();

        TerminalRenderStepMetrics.Snapshot snapshot = metrics.getAndResetDelta();
        assertEquals(1, snapshot.skippedRows);
        assertEquals(2, snapshot.visitedRows);
        assertEquals(3, snapshot.visitedCells);
        assertEquals(1, snapshot.wcWidthCalls);
        assertEquals(1, snapshot.glyphMeasureCalls);
        assertEquals(0, snapshot.drawTextRunCalls);
    }
}