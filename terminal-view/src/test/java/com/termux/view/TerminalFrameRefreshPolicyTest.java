package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalScreenSnapshot;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 规格：只有当"新帧相对已绘制帧可能改变任何像素"时，才需要产生一次新的绘制。
 *
 * <p>定位的缺陷：当前实现里 publish → onScreenUpdated() → invalidate() 是无条件的
 * （TerminalView.java:576），只要解析器产生了任何 revision（包括零单元格写入的控制序列、
 * 光标移动），就会产生一次整视口重绘。设备实测 81% 的绘制帧对应零 setChar 写入。
 *
 * <p>本文件是 TerminalFrameRefreshPolicy 的行为规格，当前应编译失败（RED）。
 */
public class TerminalFrameRefreshPolicyTest {

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

    @Test
    public void nullCurrentFrameNeedsNoDraw() {
        assertFalse(TerminalFrameRefreshPolicy.needsDraw(null, null));
    }

    @Test
    public void firstFrameAlwaysNeedsDraw() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        TerminalRenderFrame first = frame(model(emulator, null), -1, -1, -1, -1);
        assertTrue(TerminalFrameRefreshPolicy.needsDraw(null, first));
    }

    /** 核心用例：零脏行、行对象全部复用、光标与选择都没变 → 不需要任何绘制。 */
    @Test
    public void zeroDirtyRowsWithUnchangedCursorAndSelectionNeedsNoDraw() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);

        // 期间没有任何解析/模型写入：所有行对象复用，脏行位图为空，光标/选择不变。
        TerminalModelFrame m2 = model(emulator, m1.screen);
        TerminalRenderFrame f2 = frame(m2, -1, -1, -1, -1);

        for (int row = f2.topRow; row < f2.endRow; row++) {
            assertTrue("行 " + row + " 应复用上一帧的行对象", f2.rowUnchangedFrom(f1, row));
        }
        assertFalse("零可见变化的帧不应触发新的绘制",
            TerminalFrameRefreshPolicy.needsDraw(f1, f2));
    }

    @Test
    public void oneDirtyRowNeedsDraw() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);

        append(emulator, "C");
        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), -1, -1, -1, -1);
        assertTrue("有单元格写入的帧必须绘制", TerminalFrameRefreshPolicy.needsDraw(f1, f2));
    }

    /** 光标移动（零单元格写入）会改变像素，需要绘制：这是实测里 1Hz 空转刷新的来源。 */
    @Test
    public void cursorMoveWithoutCellWritesNeedsDraw() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);

        append(emulator, "\033[3;4H");
        TerminalModelFrame m2 = model(emulator, m1.screen);
        TerminalRenderFrame f2 = frame(m2, -1, -1, -1, -1);

        assertTrue("光标列/行变了", f2.cursorRow != f1.cursorRow || f2.cursorCol != f1.cursorCol);
        assertTrue(TerminalFrameRefreshPolicy.needsDraw(f1, f2));
    }

    @Test
    public void selectionChangeNeedsDraw() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);

        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), 0, 1, 3, 1);
        assertTrue(TerminalFrameRefreshPolicy.needsDraw(f1, f2));
    }

    @Test
    public void paletteChangeNeedsDraw() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);

        emulator.mColors.mCurrentColors[1] = 0x12345678;
        TerminalRenderFrame f2 = frame(model(emulator, m1.screen), -1, -1, -1, -1);
        assertTrue(f2.needsFullRedraw(f1));
        assertTrue(TerminalFrameRefreshPolicy.needsDraw(f1, f2));
    }

    @Test
    public void viewportShiftNeedsDraw() {
        TerminalEmulator emulator = emulator();
        append(emulator, "AB");
        TerminalModelFrame m1 = model(emulator, null);
        TerminalRenderFrame f1 = frame(m1, -1, -1, -1, -1);

        TerminalModelFrame m2 = model(emulator, m1.screen);
        TerminalRenderFrame shifted = new TerminalRenderFrame(m2, m2.topRow - 1, -1, -1, -1, -1);
        assertTrue(TerminalFrameRefreshPolicy.needsDraw(f1, shifted));
    }
}
