package com.termux.view;

import java.util.Arrays;

/**
 * 决策：在"画布能保留上一帧像素"的前提下，渲染一帧时哪些外部行必须访问。
 *
 * <p>纯决策类（无 Android 依赖）。与 {@link TerminalRenderer} 内联的行跳过条件逐格等价
 * （{@link TerminalRenderer#render} 的 skipCleanRows 分支与 needsRedrawForProjection）：
 * 内容未变（行对象复用）且不属于任何"投影"的行可以跳过。"投影"指当前/前帧光标行与
 * 当前/前帧选择矩形——它们的像素不在缓冲区快照里，必须每帧重画。
 *
 * <p>调用方必须仅在画布确实保留上一帧像素时才传 retainedCanvas=true；否则必须访问
 * 视口内所有行（整屏重绘），这是当前默认路径。
 */
public final class TerminalRowSkipPolicy {

    private TerminalRowSkipPolicy() {
    }

    /**
     * @param current        本帧
     * @param previous       上一帧已绘制帧；null 时不能跳过任何行
     * @param retainedCanvas 画布是否保留上一帧像素
     * @return 必须访问的外部行号，升序；retainedCanvas=false 时等于视口内全部行
     */
    public static int[] visitedRows(TerminalRenderFrame current, TerminalRenderFrame previous, boolean retainedCanvas) {
        if (current == null) return new int[0];
        final int rows = current.endRow - current.topRow;
        if (rows <= 0) return new int[0];
        int[] visited = new int[rows];
        int count = 0;
        for (int row = current.topRow; row < current.endRow; row++) {
            if (maySkipRow(current, previous, retainedCanvas, row)) continue;
            visited[count++] = row;
        }
        return Arrays.copyOf(visited, count);
    }

    /**
     * 单行跳过判定。
     *
     * @return true 表示该行的像素可由上一帧保留（无需测量与绘制）
     */
    public static boolean maySkipRow(TerminalRenderFrame current, TerminalRenderFrame previous, boolean retainedCanvas, int row) {
        if (current == null) return false;
        if (row < current.topRow || row >= current.endRow) return false;
        if (!retainedCanvas || previous == null) return false;
        if (!current.rowUnchangedFrom(previous, row)) return false;
        if (current.cursorVisible && row == current.cursorRow) return false;
        if (previous.cursorVisible && row == previous.cursorRow) return false;
        if (row >= current.selectionY1 && row <= current.selectionY2) return false;
        if (row >= previous.selectionY1 && row <= previous.selectionY2) return false;
        return true;
    }
}
