package com.termux.terminal;

import junit.framework.TestCase;

public class TerminalParserMetricsTest extends TestCase {
    public void testPhaseTimesAccumulateSeparately() {
        TerminalParserMetrics metrics = new TerminalParserMetrics();
        metrics.recordPhaseNanos(10, 20, 30, 40);
        metrics.recordPhaseNanos(1, 2, 3, 4);

        TerminalParserMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(11, snapshot.readNanos);
        assertEquals(22, snapshot.appendNanos);
        assertEquals(33, snapshot.snapshotNanos);
        assertEquals(44, snapshot.publishNanos);
    }

    public void testNegativePhaseTimesAreIgnored() {
        TerminalParserMetrics metrics = new TerminalParserMetrics();
        metrics.recordPhaseNanos(-1, 0, -2, 0);

        TerminalParserMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(0, snapshot.readNanos);
        assertEquals(0, snapshot.appendNanos);
        assertEquals(0, snapshot.snapshotNanos);
        assertEquals(0, snapshot.publishNanos);
    }
}
