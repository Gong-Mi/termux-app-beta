package com.termux.view;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalScreenSnapshot;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 规格：什么条件下允许跳过一整行的绘制（需要保留画布为前提），以及要访问哪些行。
 *
 * <p>定位的缺陷：TerminalView 里的 skipCleanRows 门控要求 View 处于
 * LAYER_TYPE_HARDWARE / LAYER_TYPE_SOFTWARE（TerminalView.java:1161-1164），
 * 而全仓没有任何 setLayerType 调用 → 该开关恒为 false → TerminalRenderer 每帧
 * 访问全部 53 行 × 95 列。本文件把"哪些行必须访问"抽成纯类规格，当前应编译失败（RED）。
 */
public class TerminalRowSkipPolicyTest {

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

    private static TerminalModelFrame model(TerminalEmulator emulator, TerminalScreenSnapshot previousScreen) {
        long[] dirty = emulator.getScreen().getAndClearDirtyRowBits();
        return new TerminalModelFrame(emulator, 0, dirty, 0, previousScreen);
    }

    private static TerminalRenderFrame frame(TerminalModelFrame model, int selx1, int sely1, int selx2, int sely2) {
        return new TerminalRenderFrame(model, model.topRow, selx1, sely1, selx2, sely2);
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static void hideCursor(TerminalEmulator emulator) {
        emulator.setCursorBlinkingEnabled(true);
        emulator.setCursorBlinkState(false);
    }

    private static int[] ints(int... values) {
        return values;
    }

    /** 没有保留画布时（当前设备就是这个状态）必须访问每一行：整屏重绘。 */
    @Test
    public void withoutRetainedCanvasEveryRowIsVisited() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);
        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), -1, -1, -1, -1);

        assertEquals(4, TerminalRowSkipPolicy.visitedRows(f2, f1, false).length);
    }

    /** 有保留画布 + 全行复用 + 光标不可见 + 无选择 → 一行都不用访问。 */
    @Test
    public void retainedCanvasWithAllCleanRowsVisitsNothing() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        hideCursor(emulator);
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);
        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), -1, -1, -1, -1);

        assertArrayEquals(ints(), TerminalRowSkipPolicy.visitedRows(f2, f1, true));
    }

    /** 只有第 1 行有写入 → 只访问第 1 行（光标不可见、无选择）。 */
    @Test
    public void oneDirtyRowVisitsExactlyThatRow() {
        TerminalEmulator emulator = emulator();
        append(emulator, "A\nB\nC");
        hideCursor(emulator);
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);

        append(emulator, "\033[2;1HZ");   // 只改第 1 行（0-based）
        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), -1, -1, -1, -1);

        assertArrayEquals(ints(1), TerminalRowSkipPolicy.visitedRows(f2, f1, true));
    }

    /** 光标可见时，光标所在行即使内容未变也必须重画（光标是投影，不在快照里）。 */
    @Test
    public void visibleCursorRowIsAlwaysVisited() {
        TerminalEmulator emulator = emulator();
        append(emulator, "A\nB");
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);
        terminalCursorTo(emulator, 2);
        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), -1, -1, -1, -1);

        assertArrayEquals(ints(f2.cursorRow), TerminalRowSkipPolicy.visitedRows(f2, f1, true));
    }

    /** 光标移动：旧光标行与新光标行都必须重画（两帧光标都可见）。 */
    @Test
    public void previousCursorRowIsVisitedWhenCursorMoves() {
        TerminalEmulator emulator = emulator();
        append(emulator, "A\nB");
        terminalCursorTo(emulator, 1);            // 光标停在 0-based 第 0 行，保持可见
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);

        terminalCursorTo(emulator, 3);            // 移到 0-based 第 2 行，全程无单元格写入
        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), -1, -1, -1, -1);

        assertTrue(f1.cursorVisible && f2.cursorVisible);
        assertEquals(0, f1.cursorRow);
        assertEquals(2, f2.cursorRow);
        assertArrayEquals(ints(0, 2), TerminalRowSkipPolicy.visitedRows(f2, f1, true));
    }

    /** 选择矩形覆盖的行即使内容未变也必须重画。 */
    @Test
    public void selectionRowsAreVisitedEvenWhenUnchanged() {
        TerminalEmulator emulator = emulator();
        append(emulator, "A\nB\nC");
        hideCursor(emulator);
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);
        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), 0, 1, 3, 2);

        assertArrayEquals(ints(1, 2), TerminalRowSkipPolicy.visitedRows(f2, f1, true));
    }

    private static void terminalCursorTo(TerminalEmulator emulator, int oneBasedRow) {
        append(emulator, "\033[" + oneBasedRow + ";1H");
    }
}
