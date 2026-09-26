package com.termux.view;

/**
 * 决策：一个新采集的帧相对"上一次实际绘制过的帧"是否可能改变任何像素——只有可能改变时
 * 才需要产生一次新的绘制。
 *
 * <p>纯决策类（无 Android 依赖）。预期调用点是 {@code TerminalView.onScreenUpdated()}：
 * 在 {@code invalidate()} 之前判断，返回 false 时跳过"发布→失效→绘制"这一轮，
 * 屏幕上保留的正是上一帧的像素。
 *
 * <p>内容变化的判据是行对象身份复用（{@link TerminalRenderFrame#rowUnchangedFrom}）：
 * 快照机制对未触碰的行返回同一个不可变行对象，该信号跨信箱丢帧也成立。
 * 光标与选择矩形是视图投影，不在快照里，单独比较。
 */
public final class TerminalFrameRefreshPolicy {

    private TerminalFrameRefreshPolicy() {
    }

    /**
     * @param previous 上一次实际绘制过的帧；null 表示尚无已绘制帧
     * @param current  刚采集到的帧；null 表示没有新帧
     * @return true 表示新一轮绘制可能改变像素（因此必须绘制）
     */
    public static boolean needsDraw(TerminalRenderFrame previous, TerminalRenderFrame current) {
        if (current == null) return false;
        if (previous == null) return true;
        if (current.needsFullRedraw(previous)) return true;
        if (cursorChanged(previous, current)) return true;
        if (selectionChanged(previous, current)) return true;
        for (int row = current.topRow; row < current.endRow; row++) {
            if (!current.rowUnchangedFrom(previous, row)) return true;
        }
        return false;
    }

    private static boolean cursorChanged(TerminalRenderFrame previous, TerminalRenderFrame current) {
        return previous.cursorVisible != current.cursorVisible
            || previous.cursorRow != current.cursorRow
            || previous.cursorCol != current.cursorCol
            || previous.cursorStyle != current.cursorStyle;
    }

    private static boolean selectionChanged(TerminalRenderFrame previous, TerminalRenderFrame current) {
        return previous.selectionX1 != current.selectionX1
            || previous.selectionY1 != current.selectionY1
            || previous.selectionX2 != current.selectionX2
            || previous.selectionY2 != current.selectionY2;
    }

    /**
     * 信箱里没有待绘制帧时的判据。
     *
     * <p>一次发布常常触发两条失效请求：帧 sink 的 request 与 app 侧 onTextChanged → onScreenUpdated。
     * 若第一条已经把"与屏上像素一致"的帧消费并 ack 掉，第二条到达时信箱已空——此时不能再把一次
     * 多余的重绘带回来。判据就是"屏上像素是否已反映最新发布的 revision"。
     *
     * @param hasRenderedFrame   是否已经画过至少一帧（没有则必须画）
     * @param lastAckedRevision  屏上像素对应的 revision，未画过时为 -1
     * @param lastPublishedRevision 最新发布过的 revision
     * @return true 表示需要绘制
     */
    public static boolean needsDrawWithoutPendingFrame(boolean hasRenderedFrame,
                                                       long lastAckedRevision,
                                                       long lastPublishedRevision) {
        if (!hasRenderedFrame) return true;
        return lastAckedRevision < lastPublishedRevision;
    }
}
