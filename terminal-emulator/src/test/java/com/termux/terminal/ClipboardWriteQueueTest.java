package com.termux.terminal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Contract of {@link ClipboardWriteQueue}: writes are serialized on one worker thread,
 * land in submission order, and survive a throwing write.
 *
 * <p>Before the queue existed each copy ran on its own freshly created thread, so the
 * first two assertions could not hold: the thread identities differ and the append order
 * is whatever the scheduler produced.</p>
 */
public class ClipboardWriteQueueTest {

    @Test
    public void writesRunOnOneWorkerThread() throws Exception {
        ClipboardWriteQueue queue = new ClipboardWriteQueue("test-clipboard-writer");
        try {
            List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(32);
            for (int i = 0; i < 32; i++) {
                queue.submit(() -> {
                    threads.add(Thread.currentThread());
                    done.countDown();
                });
            }
            assertTrue("writes did not finish", done.await(5, TimeUnit.SECONDS));
            assertTrue("queue drained", queue.awaitIdle(5_000));

            assertEquals("every write must run", 32, threads.size());
            for (Thread thread : threads) {
                assertEquals("test-clipboard-writer", thread.getName());
                assertNotSame(Thread.currentThread(), thread);
            }
            assertEquals("one and only one worker thread", 1, uniqueIdentityCount(threads));
        } finally {
            queue.shutdown();
        }
    }

    @Test
    public void writesLandInSubmissionOrder() throws Exception {
        ClipboardWriteQueue queue = new ClipboardWriteQueue("test-clipboard-order");
        try {
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 200; i++) {
                final int index = i;
                queue.submit(() -> order.add(index));
            }
            assertTrue("queue drained", queue.awaitIdle(5_000));

            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < 200; i++) expected.add(i);
            assertEquals(expected, new ArrayList<>(order));
        } finally {
            queue.shutdown();
        }
    }

    @Test
    public void throwingWriteDoesNotWedgeLaterWrites() throws Exception {
        ClipboardWriteQueue queue = new ClipboardWriteQueue("test-clipboard-throw");
        try {
            CountDownLatch afterThrow = new CountDownLatch(1);
            queue.submit(() -> {
                throw new IllegalStateException("simulated clipboard failure");
            });
            queue.submit(afterThrow::countDown);

            assertTrue("writes after a throwing write must still run",
                afterThrow.await(5, TimeUnit.SECONDS));
        } finally {
            queue.shutdown();
        }
    }

    @Test
    public void submittedNullIsIgnored() throws Exception {
        ClipboardWriteQueue queue = new ClipboardWriteQueue("test-clipboard-null");
        try {
            queue.submit(null);
            assertTrue("queue drained", queue.awaitIdle(5_000));
        } finally {
            queue.shutdown();
        }
    }

    private static int uniqueIdentityCount(List<Thread> threads) {
        List<Long> ids = new ArrayList<>();
        for (Thread thread : threads) {
            if (!ids.contains(thread.getId())) ids.add(thread.getId());
        }
        return ids.size();
    }
}
