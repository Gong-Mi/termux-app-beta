package com.termux.view;

import junit.framework.TestCase;

/** Executable contract for the backend-neutral frame request identity. */
public class TerminalFrameIdentityTest extends TestCase {

    public void testProjectionChangeIsASeparateRequestEvenWhenModelIsUnchanged() {
        TerminalFrameIdentity model = new TerminalFrameIdentity(7L, 3L, 11L, 20L);
        TerminalFrameIdentity projection = new TerminalFrameIdentity(7L, 3L, 11L, 21L);

        assertFalse(model.equals(projection));
        assertTrue(projection.isNewerThan(model));
    }

    public void testDifferentSessionOrTargetGenerationIsIncompatible() {
        TerminalFrameIdentity current = new TerminalFrameIdentity(7L, 3L, 11L, 20L);

        assertFalse(current.isCompatibleWith(new TerminalFrameIdentity(6L, 3L, 12L, 20L)));
        assertFalse(current.isCompatibleWith(new TerminalFrameIdentity(7L, 2L, 12L, 20L)));
        assertTrue(current.isCompatibleWith(new TerminalFrameIdentity(7L, 3L, 12L, 20L)));
    }

    public void testOlderModelOrProjectionRevisionIsNotNewer() {
        TerminalFrameIdentity current = new TerminalFrameIdentity(7L, 3L, 11L, 20L);

        assertFalse(current.isNewerThan(new TerminalFrameIdentity(7L, 3L, 11L, 20L)));
        assertFalse(current.isNewerThan(new TerminalFrameIdentity(7L, 3L, 12L, 19L)));
        assertFalse(current.isNewerThan(new TerminalFrameIdentity(7L, 3L, 10L, 21L)));
    }
}
