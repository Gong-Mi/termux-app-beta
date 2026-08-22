package com.termux.app.terminal;

import org.junit.Test;

import static org.junit.Assert.assertSame;

public class ClipboardPasteTargetTest {
    @Test
    public void nullRequestedSessionFallsBackToCurrentSession() {
        Object current = new Object();
        assertSame(current, ClipboardSessionResolver.resolve(null, current));
    }

    @Test
    public void explicitRequestedSessionWins() {
        Object current = new Object();
        Object requested = new Object();
        assertSame(requested, ClipboardSessionResolver.resolve(requested, current));
    }
}
