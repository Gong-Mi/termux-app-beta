package com.termux.terminal;

import junit.framework.TestCase;

public class TerminalSessionExitCoordinatorTest extends TestCase {

    public void testProcessExitWaitsForReaderCompletion() {
        TerminalSessionExitCoordinator coordinator = new TerminalSessionExitCoordinator();

        assertTrue(coordinator.markProcessExited(7));
        assertFalse(coordinator.shouldFinish(false));

        coordinator.markReaderFinished();

        assertTrue(coordinator.shouldFinish(false));
        assertEquals(7, coordinator.getExitStatus());
    }

    public void testReaderMayFinishBeforeProcessWaiter() {
        TerminalSessionExitCoordinator coordinator = new TerminalSessionExitCoordinator();

        coordinator.markReaderFinished();
        assertFalse(coordinator.shouldFinish(false));

        assertFalse(coordinator.markProcessExited(0));
        assertTrue(coordinator.shouldFinish(false));
    }

    public void testQueuedOutputMustDrainBeforeFinish() {
        TerminalSessionExitCoordinator coordinator = new TerminalSessionExitCoordinator();

        coordinator.markProcessExited(3);
        coordinator.markReaderFinished();

        assertFalse(coordinator.shouldFinish(true));
        assertTrue(coordinator.shouldFinish(false));
    }

    public void testTimeoutAllowsBoundedFallbackWithoutReaderEof() {
        TerminalSessionExitCoordinator coordinator = new TerminalSessionExitCoordinator();

        assertTrue(coordinator.markProcessExited(-9));
        coordinator.markReaderTimeout();

        assertFalse(coordinator.shouldFinish(true));
        assertTrue(coordinator.shouldFinish(false));
        assertEquals(-9, coordinator.getExitStatus());
    }

    public void testTimeoutCannotFinishBeforeProcessExit() {
        TerminalSessionExitCoordinator coordinator = new TerminalSessionExitCoordinator();

        coordinator.markReaderTimeout();

        assertFalse(coordinator.shouldFinish(false));
    }

    public void testFinishedStateCannotCompleteTwice() {
        TerminalSessionExitCoordinator coordinator = new TerminalSessionExitCoordinator();

        coordinator.markProcessExited(0);
        coordinator.markReaderFinished();
        assertTrue(coordinator.shouldFinish(false));

        coordinator.markFinished();
        coordinator.markReaderFinished();
        coordinator.markReaderTimeout();

        assertFalse(coordinator.shouldFinish(false));
    }
}
