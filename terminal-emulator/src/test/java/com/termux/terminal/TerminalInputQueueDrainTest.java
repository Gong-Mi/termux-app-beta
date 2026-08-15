package com.termux.terminal;

import java.io.ByteArrayOutputStream;

import junit.framework.TestCase;

public class TerminalInputQueueDrainTest extends TestCase {

    private static final int KB = 1024;

    public void testDrainRespectsByteBudgetAndReportsRemainingInput() {
        byte[] input = patternedBytes(64 * KB);
        ByteQueue queue = new ByteQueue(input.length);
        assertTrue(queue.write(input, 0, input.length));

        ByteArrayOutputStream consumed = new ByteArrayOutputStream();
        TerminalInputQueueDrain.Result result = TerminalInputQueueDrain.drain(
            queue, new byte[64 * KB], 32 * KB,
            (buffer, length) -> consumed.write(buffer, 0, length));

        assertEquals(32 * KB, result.getBytesRead());
        assertTrue(result.hasMore());
        byte[] actual = consumed.toByteArray();
        assertEquals(32 * KB, actual.length);
        for (int i = 0; i < actual.length; i++) {
            assertEquals(input[i], actual[i]);
        }
    }

    private static byte[] patternedBytes(int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = (byte) (i * 31);
        }
        return result;
    }
}
