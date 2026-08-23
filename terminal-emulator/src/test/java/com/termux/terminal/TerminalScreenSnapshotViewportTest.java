package com.termux.terminal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalScreenSnapshotViewportTest {
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

    @Test
    public void snapshotReportsExactlyTheExternalRowsItOwns() {
        TerminalEmulator emulator = emulator();
        byte[] history = "1\n2\n3\n4\n5\n6\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        emulator.append(history, history.length);
        TerminalScreenSnapshot snapshot = TerminalScreenSnapshot.capture(
            emulator.getScreen(), -2, 2, emulator.mColumns);

        assertTrue(snapshot.containsExternalRowRange(-2, 2));
        assertTrue(snapshot.containsExternalRowRange(-1, 1));
        assertFalse(snapshot.containsExternalRowRange(-3, 2));
        assertFalse(snapshot.containsExternalRowRange(-2, 3));
    }

    @Test
    public void viewportChangeCannotBeTreatedAsCoveredByAnOlderSnapshot() {
        TerminalEmulator emulator = emulator();
        TerminalScreenSnapshot oldViewport = TerminalScreenSnapshot.capture(
            emulator.getScreen(), 0, emulator.mRows, emulator.mColumns);

        assertFalse(oldViewport.containsExternalRowRange(-1, 0));
        assertTrue(oldViewport.containsExternalRowRange(0, emulator.mRows));
    }
}
