package com.termux.terminal;

/**
 * Immutable screen rows captured for one render frame.
 * The snapshot owns all row arrays; later TerminalBuffer mutations cannot change it.
 */
public final class TerminalScreenSnapshot {
    private final int firstExternalRow;
    private final int[] internalRows;
    private final TerminalRenderRow[] rows;

    private TerminalScreenSnapshot(int firstExternalRow, int[] internalRows, TerminalRenderRow[] rows) {
        this.firstExternalRow = firstExternalRow;
        this.internalRows = internalRows;
        this.rows = rows;
    }

    /** Capture the inclusive/exclusive external row range used by a renderer frame. */
    public static TerminalScreenSnapshot capture(TerminalBuffer screen, int firstExternalRow, int endExternalRow,
                                                  int columns) {
        if (endExternalRow < firstExternalRow) {
            throw new IllegalArgumentException("endExternalRow < firstExternalRow");
        }
        TerminalRenderRow[] rows = new TerminalRenderRow[endExternalRow - firstExternalRow];
        int[] internalRows = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            internalRows[i] = screen.externalToInternalRow(firstExternalRow + i);
            TerminalRow source = screen.allocateFullLineIfNecessary(internalRows[i]);
            rows[i] = new TerminalRenderRow(source, columns);
        }
        return new TerminalScreenSnapshot(firstExternalRow, internalRows, rows);
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
