package com.termux.terminal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import junit.framework.TestCase;

/**
 * Unit tests for {@link TerminalTextChangeCoalescer}.
 */
public class TerminalTextChangeCoalescerTest extends TestCase {

    /** A poster that records posted runnables but does not run them until told. */
    private static class RecordingPoster implements TerminalTextChangeCoalescer.Poster {
        final AtomicReference<Runnable> pending = new AtomicReference<>();
        final AtomicInteger postCount = new AtomicInteger(0);

        @Override
        public boolean post(Runnable runnable) {
            pending.set(runnable);
            postCount.incrementAndGet();
            return true;
        }

        void runPending() {
            Runnable r = pending.getAndSet(null);
            if (r != null) r.run();
        }
    }

    public void testMultipleNotificationsCollapseToOnePost() {
        RecordingPoster poster = new RecordingPoster();
        TerminalTextChangeCoalescer coalescer = new TerminalTextChangeCoalescer(poster);
        AtomicInteger callbackCount = new AtomicInteger(0);

        coalescer.notify(callbackCount::incrementAndGet);
        coalescer.notify(callbackCount::incrementAndGet);
        coalescer.notify(callbackCount::incrementAndGet);

        assertEquals("Only one runnable should be posted", 1, poster.postCount.get());
        assertEquals("Callback must not run until posted runnable executes", 0, callbackCount.get());

        poster.runPending();
        assertEquals("Exactly one callback should execute after one run", 1, callbackCount.get());
        assertEquals("No extra posts should appear", 1, poster.postCount.get());
    }

    public void testNotificationAfterRunPostsAgain() {
        RecordingPoster poster = new RecordingPoster();
        TerminalTextChangeCoalescer coalescer = new TerminalTextChangeCoalescer(poster);
        AtomicInteger callbackCount = new AtomicInteger(0);

        coalescer.notify(callbackCount::incrementAndGet);
        poster.runPending();
        assertEquals(1, callbackCount.get());

        coalescer.notify(callbackCount::incrementAndGet);
        coalescer.notify(callbackCount::incrementAndGet);
        assertEquals("Second burst should collapse to one new post", 2, poster.postCount.get());

        poster.runPending();
        assertEquals("Only one callback should execute for the second burst", 2, callbackCount.get());
    }

    public void testPostFailureReleasesPendingSlot() {
        RecordingPoster poster = new RecordingPoster() {
            int calls;
            @Override
            public boolean post(Runnable runnable) {
                calls++;
                // First call fails, second succeeds.
                if (calls == 1) return false;
                return super.post(runnable);
            }
        };
        TerminalTextChangeCoalescer coalescer = new TerminalTextChangeCoalescer(poster);
        AtomicInteger callbackCount = new AtomicInteger(0);

        coalescer.notify(callbackCount::incrementAndGet);
        assertEquals("Failed post should not leave the slot locked", 0, callbackCount.get());

        coalescer.notify(callbackCount::incrementAndGet);
        poster.runPending();
        assertEquals(1, callbackCount.get());
    }
}
