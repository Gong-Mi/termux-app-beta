package com.termux.terminal;

import java.util.Arrays;

/** Immutable row data owned by a {@link TerminalScreenSnapshot}. */
public final class TerminalRenderRow {
    private final char[] text;
    private final int spaceUsed;
    private final long[] styles;
    private final boolean lineWrap;
    /** columnStarts[c] = char index of first char belonging to display column c; length columns+1. */
    private final int[] columnStarts;

    TerminalRenderRow(TerminalRow source, int columns) {
        this.text = Arrays.copyOf(source.mText, source.mText.length);
        this.spaceUsed = source.getSpaceUsed();
        this.styles = new long[columns];
        for (int column = 0; column < columns; column++) {
            this.styles[column] = source.getStyle(column);
        }
        this.lineWrap = source.mLineWrap;
        this.columnStarts = buildColumnStarts(columns);
    }

    private int[] buildColumnStarts(int columns) {
        int[] starts = new int[columns + 1];
        starts[0] = 0;
        int currentColumn = 0;
        int currentCharIndex = 0;
        while (currentCharIndex < spaceUsed && currentColumn < columns) {
            char c = text[currentCharIndex++];
            int codePoint = Character.isHighSurrogate(c)
                ? Character.toCodePoint(c, text[currentCharIndex++])
                : c;
            int width = WcWidth.width(codePoint);
            if (width > 0) {
                currentColumn += width;
                if (currentColumn <= columns) {
                    starts[currentColumn] = currentCharIndex;
                }
            }
        }
        starts[columns] = spaceUsed;
        return starts;
    }

    /** Construct the same blank row that TerminalBuffer allocates for an unmaterialized row. */
    static TerminalRenderRow blank(int columns) {
        TerminalRenderRow row = new TerminalRenderRow(new TerminalRow(columns, 0), columns);
        return row;
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

    public boolean isLineWrapped() {
        return lineWrap;
    }

    /**
     * Return the char index of the first character belonging to the given display column.
     * This mirrors {@link com.termux.terminal.TerminalRow#findStartOfColumn(int)} without
     * the mutation caches, since render rows are immutable.
     */
    public int findStartOfColumn(int column) {
        if (column < 0) column = 0;
        if (column >= columnStarts.length) return spaceUsed;
        return columnStarts[column];
    }
}
