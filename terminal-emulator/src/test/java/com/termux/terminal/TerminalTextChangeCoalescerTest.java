package com.termux.terminal;

import junit.framework.TestCase;

import java.util.ArrayDeque;
import java.util.Queue;

public class TerminalTextChangeCoalescerTest extends TestCase {
    private static final class QueuePoster implements TerminalTextChangeCoalescer.Poster {
        final Queue<Runnable> queue = new ArrayDeque<>();
        boolean accept = true;

        @Override
        public boolean post(Runnable runnable) {
            if (!accept) return false;
            queue.add(runnable);
            return true;
        }

        void runOne() {
            assertFalse("Expected a queued runnable", queue.isEmpty());
            queue.remove().run();
        }
    }

    public void testRepeatedNotificationsQueueOneRunnable() {
        QueuePoster poster = new QueuePoster();
        TerminalTextChangeCoalescer coalescer = new TerminalTextChangeCoalescer(poster);
        int[] callbacks = {0};

        for (int i = 0; i < 1000; i++) {
            coalescer.notify(() -> callbacks[0]++);
        }

        assertEquals("Repeated notifications must share one posted runnable", 1, poster.queue.size());
        assertEquals(0, callbacks[0]);
        poster.runOne();
        assertEquals(1, callbacks[0]);
        assertTrue(poster.queue.isEmpty());
    }

    public void testNotificationCanQueueAgainAfterRunnableRuns() {
        QueuePoster poster = new QueuePoster();
        TerminalTextChangeCoalescer coalescer = new TerminalTextChangeCoalescer(poster);
        int[] callbacks = {0};

        coalescer.notify(() -> callbacks[0]++);
        poster.runOne();
        coalescer.notify(() -> callbacks[0]++);

        assertEquals(1, poster.queue.size());
        poster.runOne();
        assertEquals(2, callbacks[0]);
    }

    public void testNotificationCanQueueAgainDuringCallback() {
        QueuePoster poster = new QueuePoster();
        TerminalTextChangeCoalescer coalescer = new TerminalTextChangeCoalescer(poster);
        int[] callbacks = {0};

        coalescer.notify(() -> {
            callbacks[0]++;
            coalescer.notify(() -> callbacks[0]++);
        });
        poster.runOne();

        assertEquals("A notification raised by the callback must not lose its runnable",
                1, poster.queue.size());
        poster.runOne();
        assertEquals(2, callbacks[0]);
    }

    public void testRejectedPostDoesNotPoisonGate() {
        QueuePoster poster = new QueuePoster();
        TerminalTextChangeCoalescer coalescer = new TerminalTextChangeCoalescer(poster);
        int[] callbacks = {0};

        poster.accept = false;
        coalescer.notify(() -> callbacks[0]++);
        assertEquals(0, poster.queue.size());

        poster.accept = true;
        coalescer.notify(() -> callbacks[0]++);
        poster.runOne();
        assertEquals(1, callbacks[0]);
    }
}
