package com.termux.terminal;

import junit.framework.TestCase;

public class TerminalAppendStepMetricsTest extends TestCase {

    public void testDeltaDrainsAndResets() {
        TerminalAppendStepMetrics metrics = new TerminalAppendStepMetrics();
        metrics.recordInputBytes(100);
        metrics.recordSgrSequence();
        metrics.recordSetCharCall();

        TerminalAppendStepMetrics.Snapshot first = metrics.getAndResetDelta();
        assertEquals(100, first.inputBytes);
        assertEquals(1, first.sgrSequences);
        assertEquals(1, first.setCharCalls);

        TerminalAppendStepMetrics.Snapshot second = metrics.getAndResetDelta();
        assertEquals(0, second.inputBytes);
        assertEquals(0, second.sgrSequences);
        assertEquals(0, second.setCharCalls);
    }

    public void testMixedStepsAccumulateIndependently() {
        TerminalAppendStepMetrics metrics = new TerminalAppendStepMetrics();
        metrics.recordUtf8ContinuationByte();
        metrics.recordUtf8ContinuationByte();
        metrics.recordEscapeByte();
        metrics.recordCsiByte();
        metrics.recordCsiByte();
        metrics.recordCsiByte();
        metrics.recordOscOrDcsByte();
        metrics.recordControlByte();
        metrics.recordCodePointCall();
        metrics.recordCodePointCall();
        metrics.recordPlainEmitted();
        metrics.recordScrollOperation();
        metrics.recordSgrSequence();
        metrics.recordSgrSequence();

        TerminalAppendStepMetrics.Snapshot snapshot = metrics.getAndResetDelta();
        assertEquals(2, snapshot.utf8ContinuationBytes);
        assertEquals(1, snapshot.escapeBytes);
        assertEquals(3, snapshot.csiBytes);
        assertEquals(1, snapshot.oscOrDcsBytes);
        assertEquals(1, snapshot.controlBytes);
        assertEquals(2, snapshot.codePointCalls);
        assertEquals(1, snapshot.plainEmitted);
        assertEquals(1, snapshot.scrollOperations);
        assertEquals(2, snapshot.sgrSequences);
    }
}