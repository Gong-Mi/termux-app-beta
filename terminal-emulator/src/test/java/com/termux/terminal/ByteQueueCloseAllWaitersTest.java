package com.termux.terminal;

import junit.framework.TestCase;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Regression tests for ByteQueue multi-waiter wakeups.
 *
 * <p>ByteQueue is documented as a single-producer/single-consumer queue, but its blocking
 * {@link ByteQueue#write} is reachable from both UI keys and parser replies/paste on the
 * terminal-to-process path. Every waiter must observe a terminal close; a single
 * {@code notify()} can leave another producer parked indefinitely. Consumer cases are
 * additional queue-contract controls, not a claim that production has multiple readers.</p>
 *
 * <p>All tests use a real queue and real threads. Threads record anything they throw so a failed
 * worker cannot disappear silently, and every test releases and joins its workers in a
 * {@code finally} block so a red test cannot leak threads into the next one.</p>
 */
public class ByteQueueCloseAllWaitersTest extends TestCase {

    private static final int QUEUE_SIZE = 4;
    private static final long WAIT_TIMEOUT_MS = 5000;

    private interface Body {
        void run(Worker self) throws Exception;
    }

    private static final class Worker extends Thread {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile int readResult;
        volatile boolean writeResult;
        private final Body body;

        Worker(String name, Body body) {
            super(name);
            this.body = body;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                body.run(this);
            } catch (Throwable t) {
                failure.set(t);
            }
        }
    }

    /**
     * Blocks until the worker is parked in {@code Object.wait()} inside ByteQueue. The workers in
     * this test only ever call one blocking ByteQueue method, so WAITING is unambiguous.
     */
    private static void awaitWaiting(Worker worker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (worker.getState() == Thread.State.WAITING) return;
            if (!worker.isAlive()) break;
            Thread.sleep(5);
        }
        fail(worker.getName() + " did not block in ByteQueue: state=" + worker.getState()
            + ", failure=" + worker.failure.get());
    }

    private static boolean joinWithin(Worker worker, long timeoutMs) throws InterruptedException {
        worker.join(timeoutMs);
        return !worker.isAlive();
    }

    /** Unconditionally closes the queue, interrupts and joins every worker. */
    private static void release(ByteQueue queue, Worker... workers) {
        queue.close();
        for (Worker worker : workers) worker.interrupt();
        boolean interrupted = false;
        for (Worker worker : workers) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_TIMEOUT_MS);
            while (worker.isAlive()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) break;
                try {
                    worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        for (Worker worker : workers) assertFalse("owned test thread leaked: " + worker.getName(), worker.isAlive());
    }

    private static void assertNoFailure(Worker... workers) {
        for (Worker worker : workers) {
            Throwable failure = worker.failure.get();
            if (failure != null) {
                StringWriter trace = new StringWriter();
                failure.printStackTrace(new PrintWriter(trace));
                fail(worker.getName() + " threw an exception:\n" + trace);
            }
        }
    }

    private static void assertBytes(byte[] expected, byte[] actual, int length) {
        assertEquals("length", expected.length, length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("byte at index " + i, expected[i], actual[i]);
        }
    }

    /** Two producers blocked on a full queue must both be released by close(). */
    public void testCloseWakesAllProducersBlockedOnFullQueue() throws Exception {
        ByteQueue queue = new ByteQueue(QUEUE_SIZE);
        assertTrue(queue.write(new byte[]{1, 2, 3, 4}, 0, 4));

        Worker first = new Worker("producer-1", self ->
            self.writeResult = queue.write(new byte[]{11}, 0, 1));
        Worker second = new Worker("producer-2", self ->
            self.writeResult = queue.write(new byte[]{12}, 0, 1));

        first.start();
        second.start();
        try {
            awaitWaiting(first);
            awaitWaiting(second);

            queue.close();

            assertTrue("close() must release producer-1", joinWithin(first, WAIT_TIMEOUT_MS));
            assertTrue("close() must release producer-2", joinWithin(second, WAIT_TIMEOUT_MS));
            assertNoFailure(first, second);
            assertFalse("producer-1 must observe the closed queue", first.writeResult);
            assertFalse("producer-2 must observe the closed queue", second.writeResult);
        } finally {
            release(queue, first, second);
        }
    }

    /** Two consumers blocked on an empty queue must both be released by close(). */
    public void testCloseWakesAllConsumersBlockedOnEmptyQueue() throws Exception {
        ByteQueue queue = new ByteQueue(QUEUE_SIZE);

        Worker first = new Worker("consumer-1", self ->
            self.readResult = queue.read(new byte[1], true));
        Worker second = new Worker("consumer-2", self ->
            self.readResult = queue.read(new byte[1], true));

        first.start();
        second.start();
        try {
            awaitWaiting(first);
            awaitWaiting(second);

            queue.close();

            assertTrue("close() must release consumer-1", joinWithin(first, WAIT_TIMEOUT_MS));
            assertTrue("close() must release consumer-2", joinWithin(second, WAIT_TIMEOUT_MS));
            assertNoFailure(first, second);
            assertEquals("consumer-1 must observe end-of-stream", -1, first.readResult);
            assertEquals("consumer-2 must observe end-of-stream", -1, second.readResult);
        } finally {
            release(queue, first, second);
        }
    }

    /**
     * A normal read that frees space in a full queue must wake every blocked producer, not just
     * one; otherwise the remaining producer sleeps even though capacity is available.
     */
    public void testReadFreeingSpaceWakesAllProducersBlockedOnFullQueue() throws Exception {
        ByteQueue queue = new ByteQueue(QUEUE_SIZE);
        assertTrue(queue.write(new byte[]{1, 2, 3, 4}, 0, 4));

        Worker first = new Worker("producer-1", self ->
            self.writeResult = queue.write(new byte[]{11}, 0, 1));
        Worker second = new Worker("producer-2", self ->
            self.writeResult = queue.write(new byte[]{12}, 0, 1));

        first.start();
        second.start();
        try {
            awaitWaiting(first);
            awaitWaiting(second);

            byte[] drained = new byte[2];
            assertEquals(2, queue.read(drained, 0, 2, false));
            assertBytes(new byte[]{1, 2}, drained, 2);

            assertTrue("read() freeing space must release producer-1",
                joinWithin(first, WAIT_TIMEOUT_MS));
            assertTrue("read() freeing space must release producer-2",
                joinWithin(second, WAIT_TIMEOUT_MS));
            assertNoFailure(first, second);
            assertTrue("producer-1 must complete its write", first.writeResult);
            assertTrue("producer-2 must complete its write", second.writeResult);

            byte[] remaining = new byte[4];
            assertEquals(4, queue.read(remaining, 0, 4, false));
            // FIFO for bytes that were already queued; the two appended bytes may arrive in
            // either producer order, but each must be present exactly once.
            assertEquals(3, remaining[0]);
            assertEquals(4, remaining[1]);
            boolean inOrder = remaining[2] == 11 && remaining[3] == 12;
            boolean reversed = remaining[2] == 12 && remaining[3] == 11;
            assertTrue("appended bytes must both survive intact, got "
                + remaining[2] + "," + remaining[3], inOrder || reversed);
        } finally {
            release(queue, first, second);
        }
    }

    /** Single-consumer control: the ordinary close()-wakes-reader path keeps working. */
    public void testCloseWakesSingleBlockedConsumer() throws Exception {
        ByteQueue queue = new ByteQueue(QUEUE_SIZE);
        Worker consumer = new Worker("consumer", self ->
            self.readResult = queue.read(new byte[1], true));

        consumer.start();
        try {
            awaitWaiting(consumer);

            queue.close();

            assertTrue("close() must release the consumer", joinWithin(consumer, WAIT_TIMEOUT_MS));
            assertNoFailure(consumer);
            assertEquals("consumer must observe end-of-stream", -1, consumer.readResult);
        } finally {
            release(queue, consumer);
        }
    }
}
