package com.termux.terminal;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contract tests for the bounded parser-output queue: non-blocking whole-chunk
 * admission, copied ownership, FIFO reads that may span chunks, finite bounds
 * and close semantics shared by the PTY writer.
 */
public class TerminalOutboundQueueTest extends TestCase {

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readFully(TerminalOutboundQueue queue, int expectedBytes) {
        byte[] out = new byte[expectedBytes];
        int offset = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (offset < expectedBytes && System.nanoTime() < deadline) {
            int read = queue.read(out, offset, expectedBytes - offset, false);
            if (read < 0) fail("queue closed before expected bytes were read");
            offset += read;
            if (read == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
            }
        }
        assertEquals("queue did not deliver the expected byte count", expectedBytes, offset);
        return out;
    }

    public void testOfferCopiesPayloadAndReadSpansChunksInFifoOrder() {
        TerminalOutboundQueue queue = new TerminalOutboundQueue(64, 8);
        byte[] first = bytes("abc");
        byte[] second = bytes("de");
        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, queue.offer(first, 0, first.length));
        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, queue.offer(second, 0, second.length));

        // The caller owns its buffer again immediately after offer().
        Arrays.fill(first, (byte) 'x');

        byte[] four = new byte[4];
        assertEquals("a read may span chunk boundaries", 4, queue.read(four, 0, 4, false));
        assertTrue("abcd".equals(new String(four, StandardCharsets.US_ASCII)));
        byte[] one = new byte[1];
        assertEquals(1, queue.read(one, 0, 1, false));
        assertEquals("e", new String(one, StandardCharsets.US_ASCII));
        assertEquals(0, queue.availableBytes());
        assertFalse(queue.hasData());
    }

    public void testOfferRejectsWholeChunkOnPayloadBoundWithoutPartialAcceptance() {
        TerminalOutboundQueue queue = new TerminalOutboundQueue(10, 8);
        byte[] eight = bytes("12345678");
        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, queue.offer(eight, 0, eight.length));

        byte[] three = bytes("abc");
        assertEquals("a chunk that would cross the payload bound must be rejected whole",
            TerminalOutboundQueue.OfferResult.FULL, queue.offer(three, 0, three.length));
        assertEquals(8, queue.availableBytes());
        assertEquals("rejected bytes must not be partially admitted",
            "12345678", new String(readFully(queue, 8), StandardCharsets.US_ASCII));

        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, queue.offer(three, 0, three.length));
        assertEquals("abc", new String(readFully(queue, 3), StandardCharsets.US_ASCII));
    }

    public void testOfferRejectsWholeChunkOnChunkCountBound() {
        TerminalOutboundQueue queue = new TerminalOutboundQueue(100, 2);
        byte[] one = bytes("1");
        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, queue.offer(one, 0, 1));
        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, queue.offer(one, 0, 1));
        assertEquals("chunk-count bound must apply even when payload bytes remain",
            TerminalOutboundQueue.OfferResult.FULL, queue.offer(one, 0, 1));
        assertEquals(2, queue.chunkCount());

        assertEquals(2, queue.read(new byte[2], 0, 2, false));
        assertEquals(0, queue.chunkCount());
        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, queue.offer(one, 0, 1));
    }

    public void testZeroLengthOfferIsExplicitNoOp() {
        TerminalOutboundQueue queue = new TerminalOutboundQueue(8, 2);
        byte[] empty = new byte[0];
        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, queue.offer(empty, 0, 0));
        assertEquals(0, queue.chunkCount());
        assertEquals(0, queue.availableBytes());

        queue.close();
        assertEquals("zero bytes are still a no-op on a closed queue, reported as closed",
            TerminalOutboundQueue.OfferResult.CLOSED, queue.offer(empty, 0, 0));
    }

    public void testCloseClearsPendingBytesAndWakesBlockedReader() throws Exception {
        TerminalOutboundQueue pending = new TerminalOutboundQueue(64, 8);
        byte[] buffered = bytes("abc");
        assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED, pending.offer(buffered, 0, 3));
        pending.close();
        assertEquals("close must clear buffered output", 0, pending.availableBytes());
        assertEquals("a closed queue delivers -1 to the PTY writer", -1,
            pending.read(new byte[8], 0, 8, true));
        assertFalse(pending.isOpen());

        final TerminalOutboundQueue queue = new TerminalOutboundQueue(64, 8);
        final AtomicInteger readResult = new AtomicInteger(Integer.MIN_VALUE);
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                entered.countDown();
                readResult.set(queue.read(new byte[8], 0, 8, true));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "outbound-queue-blocked-reader");
        reader.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.sleep(50); // let the reader reach wait()
            queue.close();
            reader.join(5000);
            assertFalse("blocked reader must be released by close()", reader.isAlive());
            assertNull("reader threw on close", failure.get());
            assertEquals(-1, readResult.get());
        } finally {
            queue.close();
            reader.join(5000);
        }
    }

    public void testNonBlockingReadOnEmptyOpenQueueReturnsZeroAndClosedReturnsMinusOne() {
        TerminalOutboundQueue queue = new TerminalOutboundQueue(8, 2);
        assertEquals(0, queue.read(new byte[4], false));
        queue.close();
        assertEquals(-1, queue.read(new byte[4], false));
    }

    public void testWriterSizedReadsPreserveBytesAcrossChunkBoundaries() {
        TerminalOutboundQueue queue = new TerminalOutboundQueue(16 * 1024, 8192);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 5000; i++) builder.append((char) ('a' + (i % 26)));
        byte[] payload = bytes(builder.toString());

        // Split into uneven chunks so the 4096-byte writer copy crosses boundaries.
        int offset = 0;
        int chunk = 1;
        while (offset < payload.length) {
            int length = Math.min(chunk, payload.length - offset);
            assertEquals(TerminalOutboundQueue.OfferResult.ACCEPTED,
                queue.offer(payload, offset, length));
            offset += length;
            chunk = chunk == 7 ? 1 : chunk + 3;
        }

        byte[] result = new byte[payload.length];
        int readOffset = 0;
        while (readOffset < result.length) {
            int read = queue.read(result, readOffset, Math.min(4096, result.length - readOffset), true);
            assertTrue("blocking read must return data while open with pending bytes", read > 0);
            readOffset += read;
        }
        assertEquals("FIFO payload must survive chunk boundaries", builder.toString(),
            new String(result, StandardCharsets.US_ASCII));
    }

    public void testLegacyBooleanWriteReflectsWholeAdmission() {
        TerminalOutboundQueue queue = new TerminalOutboundQueue(4, 2);
        byte[] five = bytes("12345");
        assertFalse("legacy write is false when the chunk is rejected whole", queue.write(five, 0, 5));
        assertEquals("nothing may be admitted on rejection", 0, queue.availableBytes());
        assertTrue(queue.write(five, 0, 4));
        queue.close();
        assertFalse("legacy write is false after close", queue.write(five, 0, 1));
    }

    public void testInvalidRangesAreRejected() {
        TerminalOutboundQueue queue = new TerminalOutboundQueue(8, 2);
        byte[] two = bytes("ab");
        try {
            queue.offer(two, -1, 1);
            fail("negative offset accepted");
        } catch (IllegalArgumentException expected) {
            // contract
        }
        try {
            queue.offer(two, 1, 2);
            fail("range past end accepted");
        } catch (IllegalArgumentException expected) {
            // contract
        }
        try {
            queue.read(new byte[2], 0, 3, false);
            fail("read range past end accepted");
        } catch (IllegalArgumentException expected) {
            // contract
        }
    }
}
