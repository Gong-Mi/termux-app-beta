package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Conformance for #52's Surface generation gate (kill criterion 1: stale
 * generation rejection + old-thread join). The state machine models one
 * SurfaceView lifecycle (created -> changed* -> destroyed*) and answers the two
 * questions a render thread asks before drawing / after being handed a task:
 *
 * - may I draw FOR this generation? (draw ticket is bound to an epoch)
 * - is my epoch still the live one? (stale epochs draw nothing)
 *
 * changed() bumps the epoch: frames captured against the old geometry must be
 * refused even though the Surface object itself persisted. destroyed() detaches
 * first so join happens BEFORE the new surface can accept work.
 */
public class TerminalSurfaceGenerationGateTest {

    @Test
    public void createdIssuesTicketAndDrawsUntilDestroyed() {
        TerminalSurfaceGenerationGate gate = new TerminalSurfaceGenerationGate();
        assertEquals(0L, gate.liveEpoch());
        assertFalse(gate.canDraw(0L));

        long epoch = gate.created();
        assertTrue(epoch > 0L);
        assertTrue(gate.isLive(epoch));
        assertTrue(gate.beginDraw(epoch));

        assertTrue(gate.destroyed(epoch));
        assertFalse(gate.isLive(epoch));
        assertFalse("post-destroy draw must be refused", gate.beginDraw(epoch));
    }

    @Test
    public void changedBumpsEpochAndRefusesOldGeometryFrames() {
        TerminalSurfaceGenerationGate gate = new TerminalSurfaceGenerationGate();
        long e1 = gate.created();
        assertTrue(gate.beginDraw(e1));

        long e2 = gate.changed(e1);
        assertTrue("changed must advance the epoch", e2 > e1);
        assertTrue(gate.isLive(e2));
        assertFalse("old epoch dies at change", gate.isLive(e1));
        assertFalse(gate.beginDraw(e1));
        assertTrue(gate.beginDraw(e2));

        // A resize chain: only the latest epoch survives.
        long e3 = gate.changed(e2);
        assertFalse(gate.beginDraw(e2));
        assertTrue(gate.beginDraw(e3));
    }

    @Test
    public void destroyOfStaleEpochIsRejectedNotIgnored() {
        TerminalSurfaceGenerationGate gate = new TerminalSurfaceGenerationGate();
        long e1 = gate.created();
        gate.changed(e1);
        assertFalse("destroying an already-superseded epoch signals a bug",
            gate.destroyed(e1));
        // The live epoch was never opened with create in between; nothing else changes.
    }

    @Test
    public void recreatedSurfaceGetsNewEpochAfterDestroy() {
        TerminalSurfaceGenerationGate gate = new TerminalSurfaceGenerationGate();
        long e1 = gate.created();
        assertTrue(gate.destroyed(e1));

        long e2 = gate.created();
        assertTrue("surface can come back with a fresh epoch", e2 > e1);
        assertTrue(gate.isLive(e2));
        assertFalse(gate.isLive(e1));

        // And a destroy of the FIRST epoch arriving late (out-of-order callback)
        // is rejected instead of killing the live second surface.
        assertFalse(gate.destroyed(e1));
        assertTrue(gate.isLive(e2));
    }

    @Test
    public void epochMonotonicAcrossManyCycles() {
        TerminalSurfaceGenerationGate gate = new TerminalSurfaceGenerationGate();
        long prev = 0L;
        for (int i = 0; i < 8; i++) {
            long e = gate.created();
            assertTrue(e > prev);
            if (i % 2 == 0) {
                long ch = gate.changed(e);
                assertTrue(ch > e);
                assertTrue(gate.destroyed(ch));
            } else {
                assertTrue(gate.destroyed(e));
            }
            prev = Math.max(prev, e);
        }
    }
}
