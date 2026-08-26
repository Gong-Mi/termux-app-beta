package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Conformance for cross-buffer snapshot row reuse: {@link TerminalScreenSnapshot} may hand
 * back the SAME immutable row object only when both frames were captured from the same
 * underlying {@link TerminalBuffer}. Main↔alternate buffer switches change the buffer
 * without touching per-buffer row identity, so buffer provenance must gate reuse.
 */
public class TerminalSnapshotAltBufferReuseTest {

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

    private static void feed(TerminalEmulator emulator, String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static void clearJournal(TerminalEmulator emulator) {
        emulator.getScreen().getAndClearDirtyRowBits();
    }

    /** Control: steady-state reuse within one buffer must stay intact. */
    @Test
    public void unchangedRowsInSameBufferAreStillReused() {
        TerminalEmulator em = emulator();
        feed(em, "MAIN");
        TerminalModelFrame first = new TerminalModelFrame(em, 0,
            em.getScreen().getAndClearDirtyRowBits(), em.getScreen().getDirtyMutationCount(), null);
        clearJournal(em);

        TerminalModelFrame second = new TerminalModelFrame(em, 0, null, 0, first.screen);

        assertNotSame(second.screen, first.screen);
        assertEquals('M', second.screen.rowAtExternal(0).copyText()[0]);
        assertSameRows(first.screen, second.screen);
    }

    /** Bug guard: switching to the alternate buffer must never reuse main-buffer row objects. */
    @Test
    public void altBufferSwitchDoesNotReuseMainBufferRows() {
        TerminalEmulator em = emulator();
        feed(em, "MAIN");
        TerminalModelFrame mainFrame = new TerminalModelFrame(em, 0,
            em.getScreen().getAndClearDirtyRowBits(), em.getScreen().getDirtyMutationCount(), null);
        clearJournal(em);

        feed(em, "\u001b[?47h"); // pure alt-buffer switch: no clear-all, no main-buffer mutation
        assertTrue(em.isAlternateBufferActive());
        TerminalModelFrame altFrame = new TerminalModelFrame(em, 0, null, 0, mainFrame.screen);

        assertTrue(altFrame.alternateBufferActive);
        assertNotSame("alt snapshot must not share row objects with the main snapshot",
            altFrame.screen.rowAtExternal(0), mainFrame.screen.rowAtExternal(0));
        String text = new String(altFrame.screen.rowAtExternal(0).copyText());
        assertTrue("alt buffer starts blank, got: " + text, text.trim().isEmpty());
    }

    /** Bug guard (mirror case): returning home must show persisted main-buffer content again. */
    @Test
    public void returnToMainBufferRestoresPersistedContent() {
        TerminalEmulator em = emulator();
        feed(em, "MAIN");
        TerminalModelFrame mainFrame = new TerminalModelFrame(em, 0,
            em.getScreen().getAndClearDirtyRowBits(), em.getScreen().getDirtyMutationCount(), null);
        clearJournal(em);

        feed(em, "\u001b[?47h");
        TerminalModelFrame altFrame = new TerminalModelFrame(em, 0, null, 0, mainFrame.screen);

        feed(em, "\u001b[?47l"); // back home; main buffer untouched since its frame was captured
        assertFalse(em.isAlternateBufferActive());
        TerminalModelFrame home = new TerminalModelFrame(em, 0, null, 0, altFrame.screen);

        String text = new String(home.screen.rowAtExternal(0).copyText());
        assertTrue("main content must survive alt round-trip, got: " + text, text.startsWith("MAIN"));
    }

    private static void assertSameRows(TerminalScreenSnapshot a, TerminalScreenSnapshot b) {
        for (int externalRow = 0; externalRow < 4; externalRow++) {
            assertEquals("untouched rows must stay shared at row " + externalRow,
                a.rowAtExternal(externalRow), b.rowAtExternal(externalRow));
        }
    }
}
