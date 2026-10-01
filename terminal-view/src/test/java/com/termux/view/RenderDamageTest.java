package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RenderDamageTest {

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

    private static TerminalModelFrame model(TerminalEmulator emulator, int topRow,
                                            TerminalModelFrame previous) {
        return new TerminalModelFrame(emulator, topRow,
            emulator.getScreen().getAndClearDirtyRowBits(),
            emulator.getScreen().getDirtyMutationCount(),
            previous == null ? null : previous.screen);
    }

    private static TerminalRenderFrame frame(TerminalModelFrame model, int topRow,
                                             int selX1, int selY1, int selX2, int selY2) {
        return new TerminalRenderFrame(model, topRow, selX1, selY1, selX2, selY2);
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(data, data.length);
    }

    @Test
    public void firstFrameRequiresFullRedraw() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m = model(emulator, 0, null);
        TerminalRenderFrame current = frame(m, 0, 0, 0, -1, -1);
        RenderDamage damage = RenderDamage.compute(current, null);
        assertTrue(damage.fullRedraw);
        assertTrue(damage.geometryChanged);
        assertFalse(damage.cursorChanged);
        assertFalse(damage.selectionChanged);
    }

    @Test
    public void identicalFramesHaveNoDamage() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m1 = model(emulator, 0, null);
        TerminalRenderFrame first = frame(m1, 0, 0, 0, -1, -1);
        TerminalModelFrame m2 = model(emulator, 0, m1);
        TerminalRenderFrame second = frame(m2, 0, 0, 0, -1, -1);
        RenderDamage damage = RenderDamage.compute(second, first);
        assertFalse(damage.fullRedraw);
        assertFalse(damage.geometryChanged);
        assertFalse(damage.paletteChanged);
        assertFalse(damage.reverseVideoChanged);
        assertFalse(damage.cursorChanged);
        assertFalse(damage.selectionChanged);
    }

    @Test
    public void selectionChangeOnly() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m1 = model(emulator, 0, null);
        TerminalRenderFrame first = frame(m1, 0, 0, 0, -1, -1);
        TerminalModelFrame m2 = model(emulator, 0, m1);
        TerminalRenderFrame second = frame(m2, 0, 1, 1, 2, 2);
        RenderDamage damage = RenderDamage.compute(second, first);
        assertFalse(damage.fullRedraw);
        assertFalse(damage.geometryChanged);
        assertTrue(damage.selectionChanged);
        assertFalse(damage.cursorChanged);
    }

    @Test
    public void cursorMoveOnly() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m1 = model(emulator, 0, null);
        TerminalRenderFrame first = frame(m1, 0, 0, 0, -1, -1);
        append(emulator, "\r\n");
        TerminalModelFrame m2 = model(emulator, 0, m1);
        TerminalRenderFrame second = frame(m2, 0, 0, 0, -1, -1);
        RenderDamage damage = RenderDamage.compute(second, first);
        assertFalse(damage.fullRedraw);
        assertTrue(damage.cursorChanged);
    }

    @Test
    public void viewportScrollIsGeometryChange() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m1 = model(emulator, 0, null);
        TerminalRenderFrame first = frame(m1, 0, 0, 0, -1, -1);
        TerminalModelFrame m2 = model(emulator, 1, m1);
        TerminalRenderFrame second = frame(m2, 1, 0, 0, -1, -1);
        RenderDamage damage = RenderDamage.compute(second, first);
        assertTrue(damage.fullRedraw);
        assertTrue(damage.geometryChanged);
    }

    @Test
    public void reverseVideoChangeForcesFullRedraw() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m1 = model(emulator, 0, null);
        TerminalRenderFrame first = frame(m1, 0, 0, 0, -1, -1);
        append(emulator, "\u001b[?5h");
        TerminalModelFrame m2 = model(emulator, 0, m1);
        TerminalRenderFrame second = frame(m2, 0, 0, 0, -1, -1);
        RenderDamage damage = RenderDamage.compute(second, first);
        assertTrue(damage.fullRedraw);
        assertTrue(damage.reverseVideoChanged);
    }

    @Test
    public void paletteChangeForcesFullRedraw() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m1 = model(emulator, 0, null);
        TerminalRenderFrame first = frame(m1, 0, 0, 0, -1, -1);
        emulator.mColors.mCurrentColors[0] = 0xFF123456;
        TerminalModelFrame m2 = model(emulator, 0, m1);
        TerminalRenderFrame second = frame(m2, 0, 0, 0, -1, -1);
        RenderDamage damage = RenderDamage.compute(second, first);
        assertTrue(damage.fullRedraw);
        assertTrue(damage.paletteChanged);
    }

    @Test
    public void unchangedRowSurvivesWithoutFullRedraw() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m1 = model(emulator, 0, null);
        TerminalRenderFrame first = frame(m1, 0, 0, 0, -1, -1);
        TerminalModelFrame m2 = model(emulator, 0, m1);
        TerminalRenderFrame second = frame(m2, 0, 0, 0, -1, -1);
        RenderDamage damage = RenderDamage.compute(second, first);
        assertTrue(damage.rowUnchanged(second, first, first.topRow));
    }

    @Test
    public void fullRedrawInvalidatesRowUnchanged() {
        TerminalEmulator emulator = emulator();
        TerminalModelFrame m1 = model(emulator, 0, null);
        TerminalRenderFrame first = frame(m1, 0, 0, 0, -1, -1);
        append(emulator, "\u001b[?5h");
        TerminalModelFrame m2 = model(emulator, 0, m1);
        TerminalRenderFrame second = frame(m2, 0, 0, 0, -1, -1);
        RenderDamage damage = RenderDamage.compute(second, first);
        assertFalse(damage.rowUnchanged(second, first, first.topRow));
    }
}
