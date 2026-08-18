package com.termux.terminal;

import junit.framework.Assert;

/**
 * End-to-end dirty journal contract through TerminalEmulator.append(), not direct
 * TerminalBuffer mutation APIs. This locks the parser-to-model handoff paths.
 */
public class TerminalFeedDirtyJournalTest extends TerminalTestCase {

    private static boolean dirtyBit(long[] bits, int internalRow) {
        return bits != null && (bits[internalRow >> 6] & (1L << (internalRow & 63))) != 0;
    }

    private void clearJournal() {
        mTerminal.getScreen().getAndClearDirtyRowBits();
    }

    public void testPlainInputMarksParserOutputRow() {
        withTerminalSized(8, 4);
        clearJournal();

        enterString("A");

        TerminalBuffer screen = mTerminal.getScreen();
        long[] dirty = screen.getAndClearDirtyRowBits();
        Assert.assertTrue(dirtyBit(dirty, screen.externalToInternalRow(0)));
        Assert.assertEquals(1L, mTerminal.getScreenRevision());
    }

    public void testSgrAndCharacterFeedShareDirtyRow() {
        withTerminalSized(8, 4);
        clearJournal();

        enterString("\033[38;2;1;2;3mX");

        TerminalBuffer screen = mTerminal.getScreen();
        long[] dirty = screen.getAndClearDirtyRowBits();
        Assert.assertTrue(dirtyBit(dirty, screen.externalToInternalRow(0)));
        Assert.assertEquals(1L, mTerminal.getScreenRevision());
    }

    public void testEraseDisplayFeedMarksVisibleRows() {
        withTerminalSized(8, 4);
        enterString("A\r\nB\r\nC");
        clearJournal();

        enterString("\033[2J");

        TerminalBuffer screen = mTerminal.getScreen();
        long[] dirty = screen.getAndClearDirtyRowBits();
        for (int row = 0; row < 4; row++) {
            Assert.assertTrue("expected erased row " + row,
                dirtyBit(dirty, screen.externalToInternalRow(row)));
        }
        Assert.assertEquals(2L, mTerminal.getScreenRevision());
    }

    public void testSeparateAppendBatchesAdvanceRevisionSeparately() {
        withTerminalSized(8, 4);
        clearJournal();

        enterString("A");
        Assert.assertEquals(1L, mTerminal.getScreenRevision());
        enterString("B");
        Assert.assertEquals(2L, mTerminal.getScreenRevision());
    }

    public void testResizeAdvancesRevisionWithoutInputAppend() {
        withTerminalSized(8, 4);
        Assert.assertEquals(0L, mTerminal.getScreenRevision());

        mTerminal.resize(10, 5, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);

        Assert.assertEquals(1L, mTerminal.getScreenRevision());
    }

    public void testEmptyAppendAndUnchangedResizeDoNotAdvanceRevision() {
        withTerminalSized(8, 4);
        Assert.assertEquals(0L, mTerminal.getScreenRevision());

        mTerminal.append(new byte[0], 0);
        mTerminal.resize(8, 4, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);

        Assert.assertEquals(0L, mTerminal.getScreenRevision());
    }
}
