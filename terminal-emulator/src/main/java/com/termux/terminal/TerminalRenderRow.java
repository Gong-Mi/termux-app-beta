package com.termux.terminal;

import java.util.Arrays;

/** Immutable row data owned by a {@link TerminalScreenSnapshot}. */
public final class TerminalRenderRow {
    private final char[] text;
    private final int spaceUsed;
    private final long[] styles;

    TerminalRenderRow(TerminalRow source, int columns) {
        this.text = Arrays.copyOf(source.mText, source.mText.length);
        this.spaceUsed = source.getSpaceUsed();
        this.styles = new long[columns];
        for (int column = 0; column < columns; column++) {
            this.styles[column] = source.getStyle(column);
        }
    }

    /** Construct the same blank row that TerminalBuffer allocates for an unmaterialized row. */
    static TerminalRenderRow blank(int columns) {
        return new TerminalRenderRow(new TerminalRow(columns, 0), columns);
    }

    public char[] copyText() {
        return Arrays.copyOf(text, text.length);
    }

    /**
     * Return the immutable snapshot storage for the renderer hot path.
     *
     * The returned array must be treated as read-only. This is safe for renderer use because
     * TerminalRenderRow is immutable after construction and the snapshot is never mutated.
     */
    public char[] textForRenderer() {
        return text;
    }

    public int getSpaceUsed() {
        return spaceUsed;
    }

    public long getStyle(int column) {
        return styles[column];
    }
}
