package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Contract for {@link PaintStateCache}: caching Paint mutations must be
 * value-exact — a setter is issued if and only if the tracked value differs
 * from the requested one, and every tracked field round-trips.
 *
 * <p>Motivation (on-device 2026-09-25): drawTextRun issues up to 5 Paint
 * mutations per run for the text path (color, fakeBold, underline, skewX,
 * strikeThru; background/cursor colors are separate direct setColor sites);
 * a 777-run SGR-dense frame issues thousands of redundant setter calls on the
 * main thread (46% of one core measured while streaming). Terminal text runs
 * overwhelmingly reuse the previous run's style, so the mutations are
 * dominated by no-op repeats. The cache must never change observable paint
 * state — only elide calls that would set the same value.</p>
 */
public class PaintStateCacheTest {

    /** Recording fake sink: JVM tests must not instantiate android.graphics. */
    private static final class RecordingSink implements PaintStateCache.Sink {
        final List<String> calls = new ArrayList<>();

        @Override public void setColor(int color) {
            calls.add("color=" + (color & 0xFFFFFFFFL));
        }

        @Override public void setFakeBoldText(boolean fakeBold) {
            calls.add("bold=" + fakeBold);
        }

        @Override public void setUnderlineText(boolean underline) {
            calls.add("underline=" + underline);
        }

        @Override public void setTextSkewX(float skewX) {
            calls.add("skew=" + Float.floatToRawIntBits(skewX));
        }

        @Override public void setStrikeThruText(boolean strikeThru) {
            calls.add("strike=" + strikeThru);
        }
    }

    @Test
    public void repeatedSetColorIsElided() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setColor(0xFF00FF00);
        cache.setColor(0xFF00FF00);
        cache.setColor(0xFF00FF00);
        assertEquals("first setter must pass through, repeats must elide",
            1, sink.calls.size());
    }

    @Test
    public void changedColorIsIssued() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setColor(0xFF00FF00);
        cache.setColor(0xFF00FFFF);
        assertEquals(2, sink.calls.size());
        assertEquals("color=4278255615", sink.calls.get(1));
    }

    @Test
    public void zeroColorIsAValueNotAReset() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setColor(0);
        cache.setColor(0);
        assertEquals("0x00000000 is a valid color and must elide on repeat",
            1, sink.calls.size());
    }

    @Test
    public void booleanFieldsElideAndDistinctValuesIssue() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setFakeBoldText(true);
        cache.setFakeBoldText(true);
        cache.setFakeBoldText(false);
        cache.setFakeBoldText(false);
        assertEquals("true issued once, false issued once",
            "[bold=true, bold=false]", sink.calls.toString());

        sink.calls.clear();
        cache.setUnderlineText(true);
        cache.setUnderlineText(true);
        cache.setStrikeThruText(false);
        cache.setStrikeThruText(false);
        assertEquals("false still counts as a value: first set passes through",
            "[underline=true, strike=false]", sink.calls.toString());
    }

    @Test
    public void togglingBackIssuesSetter() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setFakeBoldText(true);
        cache.setFakeBoldText(false);
        cache.setFakeBoldText(true);
        assertEquals(3, sink.calls.size());
    }

    @Test
    public void skewExactBitComparisonNotTolerance() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setTextSkewX(-0.35f);
        cache.setTextSkewX(-0.35f);
        assertEquals("byte-identical skew elides", 1, sink.calls.size());
        cache.setTextSkewX(-0.3500001f);
        assertEquals("distinct float must forward", 2, sink.calls.size());
        cache.setTextSkewX(0f);
        cache.setTextSkewX(0f);
        assertEquals("explicit 0 is a value: forwards once then elides", 3, sink.calls.size());
    }

    @Test
    public void resetForgetsTrackedValuesSoNextSetIssues() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setColor(0xFF00FF00);
        cache.reset();
        cache.setColor(0xFF00FF00);
        assertEquals("after reset the cache must not assume the paint state",
            2, sink.calls.size());
        assertEquals("reset itself issues no paint calls", 2, sink.calls.size());
    }

    @Test
    public void resetIsPerCacheNotPerField() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setColor(0xFF00FF00);
        cache.setFakeBoldText(true);
        cache.reset();
        cache.setColor(0xFF00FF00);
        cache.setFakeBoldText(true);
        assertEquals("both fields re-issue after reset", 4, sink.calls.size());
    }

    @Test
    public void independentFieldsDoNotInterfere() {
        RecordingSink sink = new RecordingSink();
        PaintStateCache cache = new PaintStateCache(sink);
        cache.setColor(0xFF00FF00);
        cache.setFakeBoldText(true);
        cache.setColor(0xFF00FF00);
        cache.setFakeBoldText(true);
        assertEquals("interleaved repeats across fields each elide",
            2, sink.calls.size());
    }
}
