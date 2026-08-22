package com.termux.terminal;

/**
 * Immutable screen rows captured for one render frame.
 * The snapshot owns all row arrays; later TerminalBuffer mutations cannot change it.
 */
public final class TerminalScreenSnapshot {
    private final int firstExternalRow;
    private final int columns;
    private final int screenRows;
    private final int activeTranscriptRows;
    private final int[] internalRows;
    private final TerminalRenderRow[] rows;

    private TerminalScreenSnapshot(int firstExternalRow, int columns, int screenRows, int activeTranscriptRows,
                                 int[] internalRows, TerminalRenderRow[] rows) {
        this.firstExternalRow = firstExternalRow;
        this.columns = columns;
        this.screenRows = screenRows;
        this.activeTranscriptRows = activeTranscriptRows;
        this.internalRows = internalRows;
        this.rows = rows;
    }

    /** Capture the inclusive/exclusive external row range used by a renderer frame. */
    public static TerminalScreenSnapshot capture(TerminalBuffer screen, int firstExternalRow, int endExternalRow,
                                                  int columns) {
        return capture(screen, firstExternalRow, endExternalRow, columns, null, null);
    }

    /**
     * Capture a frame while reusing immutable rows from the previous capture when
     * the buffer mapping and dirty journal prove that the row did not change.
     */
    static TerminalScreenSnapshot capture(TerminalBuffer screen, int firstExternalRow, int endExternalRow,
                                          int columns, TerminalScreenSnapshot previous, long[] dirtyRowBits) {
        if (endExternalRow < firstExternalRow) {
            throw new IllegalArgumentException("endExternalRow < firstExternalRow");
        }
        TerminalRenderRow[] rows = new TerminalRenderRow[endExternalRow - firstExternalRow];
        int[] internalRows = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            int externalRow = firstExternalRow + i;
            int internalRow = screen.externalToInternalRow(externalRow);
            internalRows[i] = internalRow;
            if (canReuseRow(previous, externalRow, internalRow, columns, dirtyRowBits)) {
                rows[i] = previous.rowAtExternal(externalRow);
            } else {
                TerminalRow source = screen.allocateFullLineIfNecessary(internalRow);
                rows[i] = new TerminalRenderRow(source, columns);
            }
        }
        return new TerminalScreenSnapshot(firstExternalRow, columns, screen.mScreenRows,
            screen.getActiveTranscriptRows(), internalRows, rows);
    }

    private static boolean canReuseRow(TerminalScreenSnapshot previous, int externalRow, int internalRow,
                                       int columns, long[] dirtyRowBits) {
        if (previous == null || previous.columns != columns
                || externalRow < previous.firstExternalRow || externalRow >= previous.endExternalRow()) {
            return false;
        }
        if (dirtyRowBits != null && (internalRow >> 6) < dirtyRowBits.length
                && (dirtyRowBits[internalRow >> 6] & (1L << (internalRow & 63))) != 0) {
            return false;
        }
        return previous.internalRowAtExternal(externalRow) == internalRow;
    }

    public TerminalRenderRow rowAtExternal(int externalRow) {
        int index = externalRow - firstExternalRow;
        if (index < 0 || index >= rows.length) {
            throw new IllegalArgumentException("externalRow=" + externalRow
                + " outside [" + firstExternalRow + "," + (firstExternalRow + rows.length) + ")");
        }
        return rows[index];
    }

    public int internalRowAtExternal(int externalRow) {
        int index = externalRow - firstExternalRow;
        if (index < 0 || index >= internalRows.length) {
            throw new IllegalArgumentException("externalRow=" + externalRow
                + " outside [" + firstExternalRow + "," + (firstExternalRow + internalRows.length) + ")");
        }
        return internalRows[index];
    }

    public int firstExternalRow() {
        return firstExternalRow;
    }

    public int endExternalRow() {
        return firstExternalRow + rows.length;
    }

    /**
     * Whether the snapshot owns every external row in the half-open range.
     * Selection callers must check this before treating snapshot text as complete.
     */
    public boolean containsExternalRowRange(int startInclusive, int endExclusive) {
        return startInclusive >= firstExternalRow
            && endExclusive <= endExternalRow()
            && endExclusive >= startInclusive;
    }

    public int columns() {
        return columns;
    }

    /** Total visible screen rows (not including transcript above the viewport). */
    public int screenRows() {
        return screenRows;
    }

    /** Number of transcript rows currently above the top of the screen. */
    public int activeTranscriptRows() {
        return activeTranscriptRows;
    }

    /** Concatenate the visible text of all rows into a single string. */
    public String getTranscriptText() {
        StringBuilder sb = new StringBuilder();
        for (TerminalRenderRow row : rows) {
            int len = row.getSpaceUsed();
            if (len > 0) sb.append(row.copyText(), 0, len);
        }
        return sb.toString();
    }

    /**
     * Extract selected text from this snapshot, mirroring
     * {@link TerminalBuffer#getSelectedText(int, int, int, int, boolean, boolean)}.
     * <p>
     * Coordinates are in the external row/column space. The snapshot only owns
     * rows within its captured range; the caller must clamp coordinates to the
     * visible viewport before calling this method.
     */
    public String getSelectedText(int selX1, int selY1, int selX2, int selY2, boolean joinBackLines,
                                  boolean joinFullLines) {
        StringBuilder builder = new StringBuilder();

        if (selY1 < -activeTranscriptRows) selY1 = -activeTranscriptRows;
        if (selY2 >= screenRows) selY2 = screenRows - 1;

        for (int row = selY1; row <= selY2; row++) {
            if (row < firstExternalRow || row >= endExternalRow()) {
                // Row not captured in this snapshot: treat as blank line.
                if (row < selY2 && row < screenRows - 1) builder.append('\n');
                continue;
            }
            TerminalRenderRow lineObject = rowAtExternal(row);
            int x1 = (row == selY1) ? selX1 : 0;
            int x2;
            if (row == selY2) {
                x2 = selX2 + 1;
                if (x2 > columns) x2 = columns;
            } else {
                x2 = columns;
            }
            int x1Index = lineObject.findStartOfColumn(x1);
            int x2Index = (x2 < columns) ? lineObject.findStartOfColumn(x2) : lineObject.getSpaceUsed();
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = lineObject.findStartOfColumn(x2 + 1);
            }
            char[] line = lineObject.textForRenderer();
            int lastPrintingCharIndex = -1;
            int i;
            boolean rowLineWrap = lineObject.isLineWrapped();
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space.
                lastPrintingCharIndex = x2Index - 1;
            } else {
                for (i = x1Index; i < x2Index; ++i) {
                    char c = line[i];
                    if (c != ' ') lastPrintingCharIndex = i;
                }
            }

            int len = lastPrintingCharIndex - x1Index + 1;
            if (lastPrintingCharIndex != -1 && len > 0)
                builder.append(line, x1Index, len);

            boolean lineFillsWidth = lastPrintingCharIndex == x2Index - 1;
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < selY2 && row < screenRows - 1) builder.append('\n');
        }
        return builder.toString();
    }

    /** Convenience overload matching {@link TerminalBuffer#getSelectedText(int, int, int, int)}. */
    public String getSelectedText(int selX1, int selY1, int selX2, int selY2) {
        return getSelectedText(selX1, selY1, selX2, selY2, true, false);
    }
}
