package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Conformance (#51 matrix): after an intermediate frame is dropped by the mailbox,
 * the NEXT submitted frame plus its damage must fully describe reality relative to
 * the last DRAWN frame — unchanged rows stay provably shared, changed rows are not
 * skipped, and no full-redraw excuse is fabricated. Incremental backends rely on
 * exactly this contract when coalescing skips frames.
 */
public class TerminalFrameDropEquivalenceTest {

    private static TerminalEmulator emulator() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        return new TerminalEmulator(output, 8, 4, 13, 15, 8, null);
    }

    private static void feed(TerminalEmulator em, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        em.append(b, b.length);
    }

    /** Capture a model frame chaining row-reuse provenance from {@code previous} (may be null). */
    private static TerminalModelFrame snap(TerminalEmulator em, TerminalModelFrame previous) {
        return new TerminalModelFrame(em, 0,
            em.getScreen().getAndClearDirtyRowBits(), em.getScreen().getDirtyMutationCount(),
            previous == null ? null : previous.screen);
    }

    private static TerminalRenderFrame view(TerminalModelFrame model) {
        return new TerminalRenderFrame(model, 0, 0, -1, -1);
    }

    @Test
    public void damageAgainstLastDrawnIsCorrectAfterDroppedIntermediate() {
        TerminalEmulator em = emulator();
        feed(em, "AB");
        TerminalModelFrame m0 = snap(em, null);

        feed(em, "C");           // F1: published, consumed by nobody (mailbox replaces it)
        TerminalModelFrame m1 = snap(em, m0);

        feed(em, "D");           // F2: published while F1 was still pending
        TerminalModelFrame m2 = snap(em, m1);

        TerminalRenderFrame r0 = view(m0);
        TerminalRenderFrame r2 = view(m2);

        // Pixel truth: everything typed reached the final frame.
        String text = r2.screen.getTranscriptText();
        assertTrue("final frame must contain all input, got: " + text, text.contains("ABCD"));

        // Row identity: changed row is NOT shared with the drawn baseline...
        assertNotSame("changed row must not be flagged reusable", 
            r2.screen.rowAtExternal(0), r0.screen.rowAtExternal(0));
        // ...and untouched rows stay shared across BOTH intermediates.
        assertSame("untouched row provenance must survive the dropped intermediate",
            r2.screen.rowAtExternal(3), r0.screen.rowAtExternal(3));

        // Damage semantics against the LAST DRAWN frame (m0), skipping m1 entirely:
        RenderDamage damage = RenderDamage.compute(r2, r0);
        assertFalse("coalesced content change must not demand full redraw", damage.fullRedraw);
        assertFalse("changed row must be marked dirty", damage.rowUnchanged(r2, r0, 0));
        assertTrue("untouched row may be skipped", damage.rowUnchanged(r2, r0, 3));
        assertTrue("cursor advanced across the coalesced span", damage.cursorChanged);
    }
}

