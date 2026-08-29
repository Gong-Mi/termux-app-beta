package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminalRenderTargetGateTest {

    @Test
    public void detachInvalidatesCurrentTargetAndAttachCreatesNewGeneration() {
        TerminalRenderTargetGate gate = new TerminalRenderTargetGate();
        long first = gate.attach();
        assertTrue(gate.isCurrent(first));

        gate.detach();
        assertFalse(gate.isCurrent(first));

        long second = gate.attach();
        assertNotEquals(first, second);
        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    @Test
    public void initialGenerationCannotAuthorizeTarget() {
        TerminalRenderTargetGate gate = new TerminalRenderTargetGate();
        assertFalse(gate.isCurrent(0L));
    }
}
