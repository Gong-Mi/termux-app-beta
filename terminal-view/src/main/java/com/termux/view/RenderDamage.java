package com.termux.view;

import java.util.Arrays;

/**
 * Immutable description of what changed between two {@link TerminalRenderFrame}s.
 *
 * <p>Backends use this to decide which rows/cursors/selection can be left alone
 * and which must be redrawn. The damage is computed from the previous rendered
 * frame, not from the parser-side dirty bits, so it survives mailbox frame
 * drops.</p>
 */
public final class RenderDamage {

    public final boolean fullRedraw;
    public final boolean geometryChanged;
    public final boolean paletteChanged;
    public final boolean reverseVideoChanged;
    public final boolean cursorChanged;
    public final boolean selectionChanged;
    public final int topRow;
    public final int endRow;
    public final int columns;

    public RenderDamage(boolean fullRedraw, boolean geometryChanged, boolean paletteChanged,
                        boolean reverseVideoChanged, boolean cursorChanged, boolean selectionChanged,
                        int topRow, int endRow, int columns) {
        this.fullRedraw = fullRedraw;
        this.geometryChanged = geometryChanged;
        this.paletteChanged = paletteChanged;
        this.reverseVideoChanged = reverseVideoChanged;
        this.cursorChanged = cursorChanged;
        this.selectionChanged = selectionChanged;
        this.topRow = topRow;
        this.endRow = endRow;
        this.columns = columns;
    }

    /** Compute the damage from {@code previous} to {@code current}. */
    public static RenderDamage compute(TerminalRenderFrame current, TerminalRenderFrame previous) {
        boolean geometryChanged = false;
        boolean paletteChanged = false;
        boolean reverseVideoChanged = false;
        boolean cursorChanged = false;
        boolean selectionChanged = false;

        if (previous == null) {
            geometryChanged = true;
        } else {
            if (current.topRow != previous.topRow
                || current.endRow != previous.endRow
                || current.columns != previous.columns) {
                geometryChanged = true;
            }
            if (!Arrays.equals(current.paletteForRenderer(), previous.paletteForRenderer())) {
                paletteChanged = true;
            }
            if (current.reverseVideo != previous.reverseVideo) {
                reverseVideoChanged = true;
            }
            if (current.cursorRow != previous.cursorRow
                || current.cursorCol != previous.cursorCol
                || current.cursorStyle != previous.cursorStyle
                || current.cursorVisible != previous.cursorVisible) {
                cursorChanged = true;
            }
            if (current.selectionX1 != previous.selectionX1
                || current.selectionY1 != previous.selectionY1
                || current.selectionX2 != previous.selectionX2
                || current.selectionY2 != previous.selectionY2) {
                selectionChanged = true;
            }
        }

        boolean fullRedraw = geometryChanged || paletteChanged || reverseVideoChanged;

        return new RenderDamage(fullRedraw, geometryChanged, paletteChanged, reverseVideoChanged,
            cursorChanged, selectionChanged, current.topRow, current.endRow, current.columns);
    }

    /** Whether {@code externalRow} provably has the same pixels as in {@code previous}. */
    public boolean rowUnchanged(TerminalRenderFrame current, TerminalRenderFrame previous, int externalRow) {
        if (previous == null) return false;
        if (fullRedraw) return false;
        return current.rowUnchangedFrom(previous, externalRow);
    }

    @Override
    public String toString() {
        return "RenderDamage{"
            + "fullRedraw=" + fullRedraw
            + ", geometryChanged=" + geometryChanged
            + ", paletteChanged=" + paletteChanged
            + ", reverseVideoChanged=" + reverseVideoChanged
            + ", cursorChanged=" + cursorChanged
            + ", selectionChanged=" + selectionChanged
            + ", topRow=" + topRow
            + ", endRow=" + endRow
            + ", columns=" + columns
            + '}';
    }
}
