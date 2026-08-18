package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalScreenSnapshot;

import java.util.Arrays;

/**
 * 单帧渲染的唯一数据交接对象：在 {@link TerminalView#onDraw(android.graphics.Canvas)} 入口一次性从
 * {@link TerminalEmulator} 采集，渲染过程只读本对象，不再触碰 emulator 的实时状态。
 * <p>
 * 这样渲染侧与解析/模型侧之间的“数据流交接”有一个显式的、可检查的类型：任何人想加一个渲染输入，
 * 必须显式加在这个帧上；渲染 bug 归属（解析改了没 / 渲染用了旧值）也以本对象为准。
 * 本类不改变任何渲染行为 —— 采集的值与原先渲染时现场读取的值完全一致（同一线程，渲染期间无并发变更）。
 */
public final class TerminalRenderFrame {

    /** 外部坐标系顶行（含 transcript 滚动偏移），来自 TerminalView.mTopRow。 */
    public final int topRow;
    /** 最后一行的下一行：topRow + emulator.mRows。 */
    public final int endRow;
    /** 屏宽（字符数）。 */
    public final int columns;
    /** 光标（外部行/列坐标）、样式与可见性，渲染前快照。 */
    public final int cursorCol;
    public final int cursorRow;
    public final int cursorStyle;
    public final boolean cursorVisible;
    /** 反相显示标志。 */
    public final boolean reverseVideo;
    /** 当前调色板的不可变副本。 */
    private final int[] palette;
    /** 活动屏幕缓冲（主屏或备用屏，渲染前快照）。 */
    public final TerminalScreenSnapshot screen;
    /** 文本选择的矩形（外部坐标）。 */
    public final int selectionX1, selectionY1, selectionX2, selectionY2;
    /**
     * 渲染前采集的变更台账：自上一帧清除以来被解析/模型修改过的行（内部坐标系位图）与批次计数。
     * 用于把渲染问题归属到“解析改了哪些行” vs “渲染画了什么”。
     */
    private final long[] dirtyRowBits;
    public final int dirtyMutationCount;
    /** Parser/model batch revision observed when this frame was collected. */
    public final long screenRevision;

    public TerminalRenderFrame(TerminalEmulator emulator, int topRow, long[] dirtyRowBits, int dirtyMutationCount,
                               int selectionX1, int selectionY1, int selectionX2, int selectionY2) {
        this.topRow = topRow;
        this.endRow = topRow + emulator.mRows;
        this.columns = emulator.mColumns;
        this.cursorCol = emulator.getCursorCol();
        this.cursorRow = emulator.getCursorRow();
        this.cursorStyle = emulator.getCursorStyle();
        this.cursorVisible = emulator.shouldCursorBeVisible();
        this.reverseVideo = emulator.isReverseVideo();
        this.palette = Arrays.copyOf(emulator.mColors.mCurrentColors, emulator.mColors.mCurrentColors.length);
        this.screen = TerminalScreenSnapshot.capture(emulator.getScreen(), this.topRow, this.endRow, this.columns);
        this.screenRevision = emulator.getScreenRevision();
        this.dirtyRowBits = dirtyRowBits == null ? null : Arrays.copyOf(dirtyRowBits, dirtyRowBits.length);
        this.dirtyMutationCount = dirtyMutationCount;
        this.selectionX1 = selectionX1;
        this.selectionY1 = selectionY1;
        this.selectionX2 = selectionX2;
        this.selectionY2 = selectionY2;
    }

    public TerminalRenderFrame(TerminalModelFrame model, int selectionX1, int selectionY1, int selectionX2, int selectionY2) {
        this.topRow = model.topRow;
        this.endRow = model.endRow;
        this.columns = model.columns;
        this.cursorCol = model.cursorCol;
        this.cursorRow = model.cursorRow;
        this.cursorStyle = model.cursorStyle;
        this.cursorVisible = model.cursorVisible;
        this.reverseVideo = model.reverseVideo;
        this.palette = model.copyPalette();
        this.screen = model.screen;
        this.screenRevision = model.screenRevision;
        this.dirtyRowBits = model.copyDirtyRowBits();
        this.dirtyMutationCount = model.dirtyMutationCount;
        this.selectionX1 = selectionX1;
        this.selectionY1 = selectionY1;
        this.selectionX2 = selectionX2;
        this.selectionY2 = selectionY2;
    }

    public int[] copyPalette() {
        return Arrays.copyOf(palette, palette.length);
    }

    /**
     * 判断一个外部坐标行是否需要重绘：被解析/模型改动过，或位于光标行，或与文本选择相交。
     * 供审计/测试用；渲染器当前仍然是全量重绘，本方法只回答“哪行有正当的重绘理由”。
     */
    public final boolean rowNeedsRedraw(int externalRow) {
        if (dirtyRowBits != null) {
            int internal = screen.internalRowAtExternal(externalRow);
            if ((dirtyRowBits[internal >> 6] & (1L << (internal & 63))) != 0) return true;
        }
        if (cursorVisible && externalRow == cursorRow) return true;
        return externalRow >= selectionY1 && externalRow <= selectionY2;
    }
}