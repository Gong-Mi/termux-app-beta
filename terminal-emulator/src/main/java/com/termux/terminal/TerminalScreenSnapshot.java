package com.termux.terminal;

/**
 * Immutable screen rows captured for one render frame.
 * The snapshot owns all row arrays; later TerminalBuffer mutations cannot change it.
 */
public final class TerminalScreenSnapshot {
    private final int firstExternalRow;
    private final int columns;
    private final int[] internalRows;
    private final TerminalRenderRow[] rows;

    private TerminalScreenSnapshot(int firstExternalRow, int columns, int[] internalRows, TerminalRenderRow[] rows) {
        this.firstExternalRow = firstExternalRow;
        this.columns = columns;
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
        return new TerminalScreenSnapshot(firstExternalRow, columns, internalRows, rows);
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

    /** Concatenate the visible text of all rows into a single string. */
    public String getTranscriptText() {
        StringBuilder sb = new StringBuilder();
        for (TerminalRenderRow row : rows) {
            int len = row.getSpaceUsed();
            if (len > 0) sb.append(row.copyText(), 0, len);
        }
        return sb.toString();
    }
}
