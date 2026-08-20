package com.termux.terminal;

import junit.framework.TestCase;

import java.util.Random;

/**
 * Differential equivalence test for the TerminalRow scan caches.
 *
 * findStartOfColumn() and wideDisplayCharacterStartingAt() were changed to
 * warm-start from a per-row boundary cache. This test mutates a row with a
 * random mix of narrow/wide/supplementary/combining writes, clears and
 * interval copies, and after every mutation compares the cached
 * implementations against verbatim copies of the ORIGINAL from-scratch scans
 * (the pre-cache algorithms) for every column of the row.
 */
public class TerminalRowScanEquivalenceTest extends TestCase {

    private static final int COLUMNS = 13;

    /** Original findStartOfColumn algorithm (pre-cache), operating on raw row state. */
    static int refFindStartOfColumn(TerminalRow row, int column) {
        char[] mText = row.mText;
        int mSpaceUsed = row.getSpaceUsed();
        int mColumns = COLUMNS;
        if (column == mColumns) return mSpaceUsed;
        int currentColumn = 0;
        int currentCharIndex = 0;
        while (true) {
            int newCharIndex = currentCharIndex;
            char c = mText[newCharIndex++];
            boolean isHigh = Character.isHighSurrogate(c);
            int codePoint = isHigh ? Character.toCodePoint(c, mText[newCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint);
            if (wcwidth > 0) {
                currentColumn += wcwidth;
                if (currentColumn == column) {
                    while (newCharIndex < mSpaceUsed) {
                        if (Character.isHighSurrogate(mText[newCharIndex])) {
                            if (WcWidth.width(Character.toCodePoint(mText[newCharIndex], mText[newCharIndex + 1])) <= 0) {
                                newCharIndex += 2;
                            } else {
                                break;
                            }
                        } else if (WcWidth.width(mText[newCharIndex]) <= 0) {
                            newCharIndex++;
                        } else {
                            break;
                        }
                    }
                    return newCharIndex;
                } else if (currentColumn > column) {
                    return currentCharIndex;
                }
            }
            currentCharIndex = newCharIndex;
        }
    }

    /** Original wideDisplayCharacterStartingAt algorithm (pre-cache). */
    static boolean refWideDisplayCharacterStartingAt(TerminalRow row, int column) {
        char[] mText = row.mText;
        int mSpaceUsed = row.getSpaceUsed();
        for (int currentCharIndex = 0, currentColumn = 0; currentCharIndex < mSpaceUsed; ) {
            char c = mText[currentCharIndex++];
            int codePoint = Character.isHighSurrogate(c) ? Character.toCodePoint(c, mText[currentCharIndex++]) : c;
            int wcwidth = WcWidth.width(codePoint);
            if (wcwidth > 0) {
                if (currentColumn == column && wcwidth == 2) return true;
                currentColumn += wcwidth;
                if (currentColumn > column) return false;
            }
        }
        return false;
    }

    /** Verify the cached implementation equals the reference for every column. */
    static void assertRowMatches(TerminalRow row, String context) {
        for (int c = 0; c <= COLUMNS; c++) {
            int expected = refFindStartOfColumn(row, c);
            int actual = row.findStartOfColumn(c);
            assertEquals("findStartOfColumn(" + c + ") " + context
                    + " row=" + rowDump(row), expected, actual);
        }
        for (int c = 0; c < COLUMNS; c++) {
            boolean expected = refWideDisplayCharacterStartingAt(row, c);
            boolean actual = row.wideDisplayCharacterStartingAt(c);
            assertEquals("wideDisplayCharacterStartingAt(" + c + ") " + context
                    + " row=" + rowDump(row), expected, actual);
        }
    }

    static String rowDump(TerminalRow row) {
        StringBuilder sb = new StringBuilder();
        char[] text = row.mText;
        for (int i = 0; i < row.getSpaceUsed(); i++) {
            char ch = text[i];
            sb.append(Character.isHighSurrogate(ch) ? "\\u" + Integer.toHexString(ch) + ";" : String.valueOf(ch));
        }
        return sb.toString();
    }

    public void testRandomMutationsMatchOriginalScans() {
        Random rnd = new Random(20260820L);
        TerminalRow row = new TerminalRow(COLUMNS, 0);
        TerminalRow other = new TerminalRow(COLUMNS, 0);

        // Wide variety of code points: narrow, CJK wide, supplementary wide,
        // combining, and plain space.
        int[] pool = {
            0x20, 'a', 'Z', '0',
            0x4E2D, 0x6587, 0xFF21,          // CJK wide
            0x2500,                            // box drawing, width 1
            0x1F600, 0x1F680,                 // supplementary wide (surrogate pair)
            0x0300, 0x0301, 0x0308, 0x20D0,  // combining (width 0)
            0x200B,                            // zero-width space
            // NOTE: lone surrogates (0xD800-0xDFFF) are deliberately absent:
            // the UTF-8 decoder can never produce them, and the ORIGINAL
            // from-scratch scans overrun mSpaceUsed (ArrayIndexOutOfBounds)
            // on rows containing them, so they cannot be part of a valid
            // equivalence domain.
        };

        String[] history = new String[64];

        for (int op = 0; op < 3000; op++) {
            int codePoint = pool[rnd.nextInt(pool.length)];
            int column = rnd.nextInt(COLUMNS);
            String desc = "op" + op;
            if (rnd.nextInt(20) == 0) {
                row.clear(rnd.nextInt());
                desc += " clear";
            } else if (rnd.nextInt(25) == 0) {
                int dst = rnd.nextInt(COLUMNS - 1);
                int len = 1 + rnd.nextInt(COLUMNS - dst);
                int x1 = rnd.nextInt(COLUMNS - len + 1);
                int x2 = x1 + len;
                desc += " copy(" + x1 + "," + x2 + ")->" + dst;
                row.copyInterval(other, x1, x2, dst);
            } else {
                desc += " setChar(" + column + "," + Integer.toHexString(codePoint) + ")";
                if (column > 0) row.wideDisplayCharacterStartingAt(column - 1);
                if (column + 1 < COLUMNS) row.wideDisplayCharacterStartingAt(column + 1);
                row.findStartOfColumn(column);
                try {
                    row.setChar(column, codePoint, rnd.nextInt(7));
                } catch (IllegalArgumentException ignored) {
                    desc += " THREW";
                }
            }
            try {
                assertRowMatches(row, "after " + desc);
            } catch (Throwable e) {
                System.err.println("FAILURE at " + desc + " state=" + rowDump(row)
                        + " spaceUsed=" + row.getSpaceUsed());
                for (int i = Math.max(0, op - 5); i < op; i++) System.err.println("  prev: " + history[i % history.length]);
                throw e;
            }
            history[op % history.length] = desc;
        }
    }

    /** Same-width CJK overwrite must keep the cache hot and still be exact. */
    public void testFullLineOverwriteMatchesOriginalScans() {
        TerminalRow row = new TerminalRow(COLUMNS, 0);
        // Sequential overwrite, the benchmark's hot pattern.
        for (int round = 0; round < 50; round++) {
            for (int c = 0; c < COLUMNS - 1; c++) {
                row.setChar(c, (c % 2 == 0) ? 0x4E2D : 0x6587, 3);
            }
            assertRowMatches(row, "round " + round);
        }
    }
}