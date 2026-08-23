package com.termux.terminal;

/**
 * Locks in the truecolor SGR fast path added to {@link TerminalEmulator}.
 *
 * <p>The fast path handles only the exact 5-parameter semicolon form
 * {@code ESC[38;2;r;g;bm} / {@code ESC[48;2;r;g;bm} / {@code ESC[58;2;r;g;bm}.
 * Every other SGR form must fall through to the general path, so these tests
 * assert both the fast-path result and that non-matching shapes keep the
 * general semantics (missing params default to 0, out-of-range falls back and
 * leaves state unchanged, colon sub-parameters keep their grammar, and a
 * trailing 6th parameter still applies).</p>
 */
public class SgrTruecolorFastPathTest extends TerminalTestCase {

    public void testForegroundTruecolorFastPath() {
        withTerminalSized(2, 2);
        enterString("\033[38;2;255;127;2m");
        assertEquals(0xff000000 | (255 << 16) | (127 << 8) | 2, mTerminal.mForeColor);
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, mTerminal.mBackColor);
    }

    public void testBackgroundTruecolorFastPath() {
        withTerminalSized(2, 2);
        enterString("\033[48;2;1;2;254m");
        assertEquals(0xff000000 | (1 << 16) | (2 << 8) | 254, mTerminal.mBackColor);
    }

    public void testUnderlineTruecolorFastPath() {
        withTerminalSized(2, 2);
        enterString("\033[58;2;9;8;7m");
        assertEquals(0xff000000 | (9 << 16) | (8 << 8) | 7, mTerminal.mUnderlineColor);
    }

    public void testOmittedRgbParametersDefaultToZero() {
        withTerminalSized(2, 2);
        // 38;2;255;127;m  -> blue omitted = 0
        enterString("\033[38;2;255;127;m");
        assertEquals(0xff000000 | (255 << 16) | (127 << 8), mTerminal.mForeColor);
    }

    public void testOutOfRangeRgbFallsBackAndLeavesStateUnchanged() {
        withTerminalSized(2, 2);
        enterString("\033[38;2;1;2;3m");
        enterString("\033[38;2;300;127;2m");
        // Invalid RGB leaves the previous color in place.
        assertEquals(0xff000000 | (1 << 16) | (2 << 8) | 3, mTerminal.mForeColor);
    }

    public void testColonSubParametersDoNotUseFastPath() {
        withTerminalSized(2, 2);
        enterString("\033[0;38:2:255:127:2:48:2:1:2:254m");
        assertEquals(0xff000000 | (255 << 16) | (127 << 8) | 2, mTerminal.mForeColor);
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, mTerminal.mBackColor);
    }

    public void testTrailingParameterFallsThroughToGeneralPath() {
        withTerminalSized(2, 2);
        // 6 parameters: truecolor fg followed by SGR 1 (bold). Fast path must not swallow bold.
        enterString("\033[38;2;10;20;30;1m");
        assertEquals(0xff000000 | (10 << 16) | (20 << 8) | 30, mTerminal.mForeColor);
        assertTrue((mTerminal.mEffect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0);
    }

    public void testResetStillWorksAfterFastPath() {
        withTerminalSized(2, 2);
        enterString("\033[38;2;10;20;30m");
        enterString("\033[0m");
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, mTerminal.mForeColor);
        assertEquals(TextStyle.COLOR_INDEX_BACKGROUND, mTerminal.mBackColor);
    }

    public void testFastPathDoesNotDisturbEffectBits() {
        withTerminalSized(2, 2);
        enterString("\033[1;38;2;10;20;30m");
        assertEquals(0xff000000 | (10 << 16) | (20 << 8) | 30, mTerminal.mForeColor);
        assertTrue((mTerminal.mEffect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0);
        // A later plain 38;2;... sequence must not clear the bold bit.
        enterString("\033[38;2;1;2;3m");
        assertEquals(0xff000000 | (1 << 16) | (2 << 8) | 3, mTerminal.mForeColor);
        assertTrue((mTerminal.mEffect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0);
    }
}