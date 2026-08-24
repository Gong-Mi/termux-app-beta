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
    private long mRowCacheHits;
    private long mRowCacheMisses;
    private long mPaintSetupNanos;
    private long mDrawRectNanos;
    private long mDrawTextNanos;

    public void recordVisitedRow() { mVisitedRows++; }

    public void recordSkippedRow() { mSkippedRows++; }

    public void recordVisitedCell() { mVisitedCells++; }

    public void recordWcWidthCall() { mWcWidthCalls++; }

    public void recordGlyphMeasureCall() { mGlyphMeasureCalls++; }

    public void recordDrawTextRunCall() { mDrawTextRunCalls++; }

    public void recordDrawRectCall() { mDrawRectCalls++; }

    public void recordRowCacheHit() { mRowCacheHits++; }

    public void recordRowCacheMiss() { mRowCacheMisses++; }

    public void recordPaintSetupNanos(long nanos) { mPaintSetupNanos += nanos; }
    public void recordDrawRectNanos(long nanos) { mDrawRectNanos += nanos; }
    public void recordDrawTextNanos(long nanos) { mDrawTextNanos += nanos; }

    /** Drain accumulated counters; returns a point-in-time snapshot. */
    public Snapshot getAndResetDelta() {
        Snapshot snapshot = new Snapshot(mVisitedRows, mSkippedRows, mVisitedCells,
            mWcWidthCalls, mGlyphMeasureCalls, mDrawTextRunCalls, mDrawRectCalls,
            mRowCacheHits, mRowCacheMisses,
            mPaintSetupNanos, mDrawRectNanos, mDrawTextNanos);
        mVisitedRows = 0;
        mSkippedRows = 0;
        mVisitedCells = 0;
        mWcWidthCalls = 0;
        mGlyphMeasureCalls = 0;
        mDrawTextRunCalls = 0;
        mDrawRectCalls = 0;
        mRowCacheHits = 0;
        mRowCacheMisses = 0;
        mPaintSetupNanos = 0;
        mDrawRectNanos = 0;
        mDrawTextNanos = 0;
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
        public final long rowCacheHits;
        public final long rowCacheMisses;
        public final long paintSetupNanos;
        public final long drawRectNanos;
        public final long drawTextNanos;

        Snapshot(long visitedRows, long skippedRows, long visitedCells,
                 long wcWidthCalls, long glyphMeasureCalls, long drawTextRunCalls,
                 long drawRectCalls, long rowCacheHits, long rowCacheMisses,
                 long paintSetupNanos, long drawRectNanos,
                 long drawTextNanos) {
            this.visitedRows = visitedRows;
            this.skippedRows = skippedRows;
            this.visitedCells = visitedCells;
            this.wcWidthCalls = wcWidthCalls;
            this.glyphMeasureCalls = glyphMeasureCalls;
            this.drawTextRunCalls = drawTextRunCalls;
            this.drawRectCalls = drawRectCalls;
            this.rowCacheHits = rowCacheHits;
            this.rowCacheMisses = rowCacheMisses;
            this.paintSetupNanos = paintSetupNanos;
            this.drawRectNanos = drawRectNanos;
            this.drawTextNanos = drawTextNanos;
        }
    }
}