package com.termux.view;

import junit.framework.TestCase;

public class TerminalFrameInvalidationGateTest extends TestCase {
    private static final class Poster implements TerminalFrameInvalidationGate.Poster {
        Runnable pending;
        boolean accept = true;

        @Override
        public boolean post(Runnable runnable) {
            if (!accept) return false;
            if (pending != null) throw new AssertionError("more than one callback posted");
            pending = runnable;
            return true;
        }

        void runPending() {
            Runnable runnable = pending;
            pending = null;
            if (runnable == null) throw new AssertionError("no callback pending");
            runnable.run();
        }
    }

    public void testRepeatedRequestsUseOnePendingCallback() {
        Poster poster = new Poster();
        TerminalFrameInvalidationGate gate = new TerminalFrameInvalidationGate(poster);
        int[] invalidations = {0};

        gate.request(() -> invalidations[0]++);
        gate.request(() -> invalidations[0]++);
        assertEquals(0, invalidations[0]);

        poster.runPending();
        assertEquals(1, invalidations[0]);
    }

    public void testRequestDuringCallbackQueuesFollowUp() {
        Poster poster = new Poster();
        TerminalFrameInvalidationGate gate = new TerminalFrameInvalidationGate(poster);
        int[] invalidations = {0};

        gate.request(() -> {
            invalidations[0]++;
            gate.request(() -> invalidations[0]++);
        });
        poster.runPending();
        assertEquals(1, invalidations[0]);
        poster.runPending();
        assertEquals(2, invalidations[0]);
    }

    public void testRejectedPostReopensGate() {
        Poster poster = new Poster();
        poster.accept = false;
        TerminalFrameInvalidationGate gate = new TerminalFrameInvalidationGate(poster);

        gate.request(() -> fail("rejected post must not run"));
        poster.accept = true;
        gate.request(() -> { });
        poster.runPending();
    }
}
