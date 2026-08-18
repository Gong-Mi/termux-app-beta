package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class TerminalRenderFrameTest {

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
    public void frameCarriesParserRevisionAndDirtyRows() {
        TerminalEmulator emulator = emulator();
        emulator.getScreen().getAndClearDirtyRowBits();
        byte[] input = "A".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);

        TerminalRenderFrame frame = new TerminalRenderFrame(emulator, 0,
            emulator.getScreen().getAndClearDirtyRowBits(),
            emulator.getScreen().getDirtyMutationCount(), 0, 0, -1, -1);

        assertEquals(1L, frame.screenRevision);
        assertTrue(frame.rowNeedsRedraw(0));
        assertFalse(frame.rowNeedsRedraw(2));
    }

    @Test
    public void snapshotDoesNotChangeAfterEmulatorMutation() {
        TerminalEmulator emulator = emulator();
        byte[] input = "A".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        emulator.getScreen().getAndClearDirtyRowBits();
        TerminalRenderFrame frame = new TerminalRenderFrame(emulator, 0, null, 0, 0, 0, -1, -1);

        emulator.append("B".getBytes(StandardCharsets.UTF_8), 1);
        emulator.mColors.mCurrentColors[0] = 0x12345678;

        assertEquals('A', frame.screen.rowAtExternal(0).copyText()[0]);
        assertEquals(1L, frame.screenRevision);
        assertFalse(frame.copyPalette()[0] == 0x12345678);
    }

    @Test
    public void snapshotPreservesNullRowsAsBlankRows() {
        TerminalEmulator emulator = emulator();
        TerminalRenderFrame frame = new TerminalRenderFrame(emulator, 0, null, 0, 0, 0, -1, -1);

        assertEquals(' ', frame.screen.rowAtExternal(0).copyText()[0]);
        assertTrue(frame.screen.rowAtExternal(0).getStyle(0) != 0);
    }

    @Test
    public void snapshotPreservesWideCombiningAndStyleData() {
        TerminalEmulator emulator = emulator();
        byte[] input = "\033[31m界e\u0301".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        TerminalRenderFrame frame = new TerminalRenderFrame(emulator, 0, null, 0, 0, 0, -1, -1);
        char[] text = frame.screen.rowAtExternal(0).copyText();

        assertEquals('界', text[0]);
        assertEquals('e', text[1]);
        assertEquals('\u0301', text[2]);
        assertTrue(frame.screen.rowAtExternal(0).getSpaceUsed() >= 3);
        assertTrue(frame.screen.rowAtExternal(0).getStyle(0) != 0);
    }

    @Test
    public void cursorAndSelectionAddRedrawReasons() {
        TerminalEmulator emulator = emulator();
        emulator.getScreen().getAndClearDirtyRowBits();
        TerminalRenderFrame frame = new TerminalRenderFrame(emulator, 0,
            null, 0, 0, 0, 2, 2);

        assertTrue(frame.rowNeedsRedraw(frame.cursorRow));
        assertTrue(frame.rowNeedsRedraw(1));
        assertTrue(frame.rowNeedsRedraw(2));
        assertFalse(frame.rowNeedsRedraw(3));
    }
}
