package com.termux.view;

import android.graphics.Paint;

/**
 * Value-exact elision cache for the {@link Paint} mutations issued by
 * {@link TerminalRenderer#drawTextRun}.
 *
 * <p>Every setter is forwarded if and only if the requested value differs
 * from the last value this cache issued (or after {@link #reset()}). The
 * cache never changes observable paint state: a skipped call is exactly a call
 * that would have set the value the paint already holds.</p>
 *
 * <p>Terminal text runs overwhelmingly reuse the previous run's style, so the
 * drawTextRun hot path is dominated by no-op repeats of setColor /
 * setFakeBoldText / setUnderlineText / setTextSkewX / setStrikeThruText
 * (measured on device 2026-09-25: thousands of redundant setter calls per
 * SGR-dense frame on the 46%-of-one-core main thread).</p>
 *
 * <p>Float comparisons are exact ({@code ==}), not tolerance-based: drawTextRun
 * computes skew as the constant {@code -0.35f} or {@code 0.f}, so any two
 * distinct requests must forward and byte-identical requests must elide.</p>
 *
 * <p>The paint mutations remain driven through the real {@link Paint} in
 * production. The sink indirection exists only so JVM unit tests can verify
 * elision without instantiating android.graphics.</p>
 */
final class PaintStateCache {

    /** Minimal surface of Paint used by the renderer's text path. */
    interface Sink {
        void setColor(int color);
        void setFakeBoldText(boolean fakeBold);
        void setUnderlineText(boolean underline);
        void setTextSkewX(float skewX);
        void setStrikeThruText(boolean strikeThru);
    }

    private static final long UNSET = Long.MIN_VALUE;

    private final Sink mSink;

    // Tracked values packed with sentinel UNSET so 0x00000000 (a valid color)
    // is not confused with "never set". Booleans tracked as 0/1 in longs for
    // uniform packing; floats tracked via Float.floatToRawIntBits for exact
    // comparison (no tolerance).
    private long mTrackedColor = UNSET;
    private long mTrackedFakeBold = UNSET;
    private long mTrackedUnderline = UNSET;
    private long mTrackedSkewBits = UNSET;
    private long mTrackedStrikeThru = UNSET;

    PaintStateCache(Paint paint) {
        this(new PaintSink(paint));
    }

    PaintStateCache(Sink sink) {
        mSink = sink;
    }

    void setColor(int color) {
        final long packed = color & 0xFFFFFFFFL;
        if (mTrackedColor == UNSET || mTrackedColor != packed) {
            mTrackedColor = packed;
            mSink.setColor(color);
        }
    }

    void setFakeBoldText(boolean fakeBold) {
        final long packed = fakeBold ? 1 : 0;
        if (mTrackedFakeBold == UNSET || mTrackedFakeBold != packed) {
            mTrackedFakeBold = packed;
            mSink.setFakeBoldText(fakeBold);
        }
    }

    void setUnderlineText(boolean underline) {
        final long packed = underline ? 1 : 0;
        if (mTrackedUnderline == UNSET || mTrackedUnderline != packed) {
            mTrackedUnderline = packed;
            mSink.setUnderlineText(underline);
        }
    }

    void setTextSkewX(float skewX) {
        final long packed = Float.floatToRawIntBits(skewX);
        if (mTrackedSkewBits == UNSET || mTrackedSkewBits != packed) {
            mTrackedSkewBits = packed;
            mSink.setTextSkewX(skewX);
        }
    }

    void setStrikeThruText(boolean strikeThru) {
        final long packed = strikeThru ? 1 : 0;
        if (mTrackedStrikeThru == UNSET || mTrackedStrikeThru != packed) {
            mTrackedStrikeThru = packed;
            mSink.setStrikeThruText(strikeThru);
        }
    }

    /**
     * Forget all tracked values without touching the paint. The next setter
     * call for any field forwards unconditionally. Callers must use this when
     * the paint may have been mutated outside this cache (e.g. the renderer's
     * own direct {@code mTextPaint.setColor} for background/cursor rects).
     */
    void reset() {
        mTrackedColor = UNSET;
        mTrackedFakeBold = UNSET;
        mTrackedUnderline = UNSET;
        mTrackedSkewBits = UNSET;
        mTrackedStrikeThru = UNSET;
    }

    /** Production sink over the real android.graphics.Paint. */
    private static final class PaintSink implements Sink {
        private final Paint mPaint;

        PaintSink(Paint paint) {
            mPaint = paint;
        }

        @Override
        public void setColor(int color) {
            mPaint.setColor(color);
        }

        @Override
        public void setFakeBoldText(boolean fakeBold) {
            mPaint.setFakeBoldText(fakeBold);
        }

        @Override
        public void setUnderlineText(boolean underline) {
            mPaint.setUnderlineText(underline);
        }

        @Override
        public void setTextSkewX(float skewX) {
            mPaint.setTextSkewX(skewX);
        }

        @Override
        public void setStrikeThruText(boolean strikeThru) {
            mPaint.setStrikeThruText(strikeThru);
        }
    }
}
