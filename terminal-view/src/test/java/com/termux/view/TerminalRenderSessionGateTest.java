package com.termux.view;

import junit.framework.TestCase;

public class TerminalRenderSessionGateTest extends TestCase {
    public void testAdvancingInvalidatesPreviousGeneration() {
        TerminalRenderSessionGate gate = new TerminalRenderSessionGate();
        long first = gate.advance();
        long second = gate.advance();

        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    public void testInitialGenerationCannotAuthorizeCallback() {
        TerminalRenderSessionGate gate = new TerminalRenderSessionGate();
        assertFalse(gate.isCurrent(0));
    }
}
