package com.termux.view;

/**
 * Per-render step counters for the terminal renderer.
 *
 * <p>Mirrors {@code TerminalAppendStepMetrics} on the parser side: the goal is
 * to attribute the renderer's per-frame cost to concrete steps (cell scans,
 * width lookups, glyph measurement, Canvas draw calls) without changing any
 * drawing behavior. Counters are only touched by the UI thread; deltas are
 * drained by the debug diagnostics after each frame.</p>
 */
public final class TerminalRenderStepMetrics {

    private long mVisitedRows;
    private long mSkippedRows;
    private long mVisitedCells;
    private long mWcWidthCalls;
    private long mGlyphMeasureCalls;
    private long mDrawTextRunCalls;
    private long mDrawRectCalls;

    public void recordVisitedRow() { mVisitedRows++; }

    public void recordSkippedRow() { mSkippedRows++; }

    public void recordVisitedCell() { mVisitedCells++; }

    public void recordWcWidthCall() { mWcWidthCalls++; }

    public void recordGlyphMeasureCall() { mGlyphMeasureCalls++; }

    public void recordDrawTextRunCall() { mDrawTextRunCalls++; }

    public void recordDrawRectCall() { mDrawRectCalls++; }

    /** Drain accumulated counters; returns a point-in-time snapshot. */
    public Snapshot getAndResetDelta() {
        Snapshot snapshot = new Snapshot(mVisitedRows, mSkippedRows, mVisitedCells,
            mWcWidthCalls, mGlyphMeasureCalls, mDrawTextRunCalls, mDrawRectCalls);
        mVisitedRows = 0;
        mSkippedRows = 0;
        mVisitedCells = 0;
        mWcWidthCalls = 0;
        mGlyphMeasureCalls = 0;
        mDrawTextRunCalls = 0;
        mDrawRectCalls = 0;
        return snapshot;
    }

    /** Immutable point-in-time render step counters. */
    public static final class Snapshot {
        public final long visitedRows;
        public final long skippedRows;
        public final long visitedCells;
        public final long wcWidthCalls;
        public final long glyphMeasureCalls;
        public final long drawTextRunCalls;
        public final long drawRectCalls;

        Snapshot(long visitedRows, long skippedRows, long visitedCells,
                 long wcWidthCalls, long glyphMeasureCalls, long drawTextRunCalls,
                 long drawRectCalls) {
            this.visitedRows = visitedRows;
            this.skippedRows = skippedRows;
            this.visitedCells = visitedCells;
            this.wcWidthCalls = wcWidthCalls;
            this.glyphMeasureCalls = glyphMeasureCalls;
            this.drawTextRunCalls = drawTextRunCalls;
            this.drawRectCalls = drawRectCalls;
        }
    }
}