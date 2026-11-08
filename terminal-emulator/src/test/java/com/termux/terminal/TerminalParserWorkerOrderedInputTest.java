package com.termux.terminal;

import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Acceptance tests for caller-input ordering through the real parser worker.
 *
 * <p>No PTY and no process: the emulator's {@link TerminalOutput} is a
 * capturing stand-in for the session transport. Raw keys, pastes and mouse
 * reports are admitted through {@link TerminalParserWorker#requestWrite},
 * {@link TerminalParserWorker#requestPaste} and
 * {@link TerminalParserWorker#requestSendMouseEvent}; the tests assert the exact
 * transport bytes and their total order, that a paste is one atomic chunk, and
 * that admission stays bounded without partial acceptance.</p>
 */
public class TerminalParserWorkerOrderedInputTest extends TestCase {

    private static final int COLUMNS = 40;
    private static final int ROWS = 6;
    private int barrierColumns = COLUMNS;
    private final AtomicReference<TerminalModelFrame> observedFrame = new AtomicReference<>();

    private static final class CapturingOutput extends TerminalOutput {
        private final List<String> writes = new ArrayList<>();

        @Override
        public synchronized void write(byte[] data, int offset, int count) {
            writes.add(new String(data, offset, count, StandardCharsets.UTF_8));
        }

        synchronized List<String> snapshotWrites() {
            return new ArrayList<>(writes);
        }

        synchronized int writeCalls() {
            return writes.size();
        }

        synchronized String joined() {
            StringBuilder builder = new StringBuilder();
            for (String write : writes) builder.append(write);
            return builder.toString();
        }

        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private static final class TestClient {
        final CountDownLatch textChanged = new CountDownLatch(1);

        final TerminalSessionClient proxy = (TerminalSessionClient) Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(),
            new Class<?>[] { TerminalSessionClient.class },
            (p, m, a) -> {
                if ("onTextChanged".equals(m.getName())) textChanged.countDown();
                return null;
            });
    }

    private CapturingOutput output;
    private TerminalEmulator emulator;
    private ByteQueue inputQueue;
    private TerminalParserWorker worker;
    private TestClient client;
    private final AtomicReference<Throwable> escaped = new AtomicReference<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        output = new CapturingOutput();
        client = new TestClient();
        emulator = new TerminalEmulator(output, COLUMNS, ROWS, 8, 16, null, client.proxy);
        inputQueue = new ByteQueue(64 * 1024);
        worker = new TerminalParserWorker(emulator, inputQueue, observedFrame::set, client.proxy, null, 4096, 32768);
        Field threadField = TerminalParserWorker.class.getDeclaredField("mThread");
        threadField.setAccessible(true);
        ((Thread) threadField.get(worker)).setUncaughtExceptionHandler((t, e) -> escaped.set(e));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            worker.stop();
            assertTrue("parser worker leaked", worker.awaitStopped(5000));
            assertNull("parser thread escaped with an exception", escaped.get());
        } finally {
            inputQueue.close();
            super.tearDown();
        }
    }

    private void startWorker() {
        worker.start();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private void enableBracketedPaste() {
        byte[] sequence = utf8("\033[?2004h");
        emulator.append(sequence, sequence.length);
        assertTrue(emulator.isBracketedPasteModeEnabled());
    }

    private void enableSgrMouse() {
        byte[] sequence = utf8("\033[?1006h");
        emulator.append(sequence, sequence.length);
    }

    /**
     * A distinct geometry command is enqueued after the inputs. An already-running
     * coalesced APPEND can consume newly added PTY bytes before later keys, so a
     * transcript marker is not a queue barrier. Only this resize can publish the
     * requested new width; its frame proves prior commands actually completed.
     */
    private void awaitAllInputProcessed() throws Exception {
        int targetColumns = ++barrierColumns;
        worker.requestResize(targetColumns, ROWS, 8, 16);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            TerminalModelFrame frame = observedFrame.get();
            if (frame != null && frame.columns == targetColumns) return;
            Thread.sleep(2);
        }
        fail("timed out waiting for the specific resize barrier: " + targetColumns);
    }

    private void assertInputBudgetReleased() {
        assertEquals("pending input bytes must be released after execution",
            0, worker.getPendingInputBytes());
        assertEquals("pending input commands must be released after execution",
            0, worker.getPendingInputCommandCount());
    }

    /** Rejected callers must not allocate a payload copy before entering the admission gate. */
    private void assertRejectedInputDoesNotCopyBeforeAdmission(boolean closed) throws Exception {
        Class<?> beanType = Class.forName("com.sun.management.ThreadMXBean");
        Object bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null);
        assertTrue("this JVM must expose thread allocation counters",
            (Boolean) beanType.getMethod("isThreadAllocatedMemorySupported").invoke(bean));
        if (!(Boolean) beanType.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean))
            beanType.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
        if (closed) worker.stop();
        else assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
            worker.requestWrite(new byte[TerminalParserWorker.MAX_PENDING_INPUT_BYTES], 0,
                TerminalParserWorker.MAX_PENDING_INPUT_BYTES));
        byte[] payload = new byte[TerminalParserWorker.MAX_PENDING_INPUT_BYTES / 2];
        Field field = TerminalParserWorker.class.getDeclaredField("mInputAdmissionLock");
        field.setAccessible(true);
        Object gate = field.get(worker);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<TerminalParserWorker.InputAdmission> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            entered.countDown();
            try { result.set(worker.requestWrite(payload, 0, payload.length)); }
            catch (Throwable fault) { error.set(fault); }
        }, "owned-input-admission-allocation-probe");
        try {
            synchronized (gate) {
                producer.start();
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (producer.getState() != Thread.State.BLOCKED && producer.isAlive() &&
                       System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals("producer must be at the actual admission gate", Thread.State.BLOCKED, producer.getState());
                long allocated = (Long) beanType.getMethod("getThreadAllocatedBytes", long.class)
                    .invoke(bean, producer.getId());
                assertTrue("allocation counter unavailable", allocated >= 0);
                assertTrue("rejected caller copied payload before capacity/close check: " + allocated,
                    allocated < payload.length / 4);
            }
        } finally {
            producer.join(5000);
            assertFalse("allocation-probe producer leaked", producer.isAlive());
            worker.abort();
        }
        assertNull(error.get());
        assertEquals(closed ? TerminalParserWorker.InputAdmission.CLOSED : TerminalParserWorker.InputAdmission.OVERLOADED,
            result.get());
        assertInputBudgetReleased();
    }

    public void testFullInputBudgetDoesNotAllocateBeforeAdmission() throws Exception {
        assertRejectedInputDoesNotCopyBeforeAdmission(false);
    }

    public void testClosedInputDoesNotAllocateBeforeAdmission() throws Exception {
        assertRejectedInputDoesNotCopyBeforeAdmission(true);
    }

    public void testDoubleReleaseCannotHideAccountingUnderflow() throws Exception {
        java.lang.reflect.Method release = TerminalParserWorker.class.getDeclaredMethod("releaseInputCharge", int.class);
        release.setAccessible(true);
        try {
            release.invoke(worker, 1);
            fail("double release was silently clamped to zero");
        } catch (java.lang.reflect.InvocationTargetException error) {
            assertTrue(error.getCause() instanceof IllegalStateException);
        }
        assertInputBudgetReleased();
    }

    public void testKeyPasteKeyKeepsTotalOrderAndPasteIsOneChunk() throws Exception {
        enableBracketedPaste();
        startWorker();

        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
            worker.requestWrite(utf8("A"), 0, 1));
        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED, worker.requestPaste("x\r\ny"));
        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
            worker.requestWrite(utf8("B"), 0, 1));
        awaitAllInputProcessed();

        assertEquals("raw key, paste and raw key must stay in submission order, with the paste atomic",
            Arrays.asList("A", "\033[200~x\ry\033[201~", "B"), output.snapshotWrites());
        assertInputBudgetReleased();
    }

    public void testParserProtocolReplyStaysAtItsCommandPosition() throws Exception {
        startWorker();

        byte[] dsr = utf8("\033[6n");
        assertTrue(inputQueue.write(dsr, 0, dsr.length));
        worker.requestAppend();
        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
            worker.requestWrite(utf8("K"), 0, 1));
        awaitAllInputProcessed();

        assertEquals("DSR reply is emitted while processing its append command, before the later key",
            Arrays.asList("\033[1;1R", "K"), output.snapshotWrites());
        assertInputBudgetReleased();
    }

    public void testPasteCleaningBracketingAndUtf8AreByteEquivalent() throws Exception {
        enableBracketedPaste();
        startWorker();

        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
            worker.requestPaste("a\r\nb\nc\u001bd\u009fe\uD83D\uDE00\t"));
        awaitAllInputProcessed();

        assertEquals(Arrays.asList("\033[200~a\rb\rcde\uD83D\uDE00\t\033[201~"),
            output.snapshotWrites());
        assertInputBudgetReleased();
    }

    public void testEmptyPasteWithBracketedModeOffWritesNothing() throws Exception {
        startWorker();

        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED, worker.requestPaste(""));
        awaitAllInputProcessed();

        assertTrue("an empty unbracketed paste is a transport no-op", output.snapshotWrites().isEmpty());
        assertInputBudgetReleased();
    }

    public void testEmptyPasteWithBracketedModeOnWritesOnlyMarkers() throws Exception {
        enableBracketedPaste();
        startWorker();

        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED, worker.requestPaste(""));
        awaitAllInputProcessed();

        assertEquals(Arrays.asList("\033[200~\033[201~"), output.snapshotWrites());
        assertInputBudgetReleased();
    }

    public void testRawKeyBurstPreservesEveryByteInOrder() throws Exception {
        startWorker();

        for (int i = 0; i < 1000; i++) {
            assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
                worker.requestWrite(utf8("x"), 0, 1));
        }
        awaitAllInputProcessed();

        String joined = output.joined();
        assertEquals("every admitted key must reach the transport exactly once", 1000, joined.length());
        for (int i = 0; i < joined.length(); i++) {
            assertEquals("byte " + i + " out of order", 'x', joined.charAt(i));
        }
        assertInputBudgetReleased();
    }

    public void testOversizedRawWriteIsRejectedWholeAndChargesNothing() {
        byte[] oversized = new byte[TerminalParserWorker.MAX_PENDING_INPUT_BYTES + 1];
        assertEquals("a chunk larger than the pending-input budget must be rejected, not truncated",
            TerminalParserWorker.InputAdmission.OVERLOADED,
            worker.requestWrite(oversized, 0, oversized.length));
        assertTrue(output.snapshotWrites().isEmpty());
        assertEquals(0, worker.getPendingInputBytes());
        assertEquals(0, worker.getPendingInputCommandCount());
    }

    public void testOversizedPasteIsRejectedWholeAndChargesNothing() {
        int chars = TerminalParserWorker.MAX_PENDING_INPUT_BYTES / 3 + 1;
        StringBuilder text = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) text.append('a');

        assertEquals("a paste whose conservative charge exceeds the budget must be rejected whole",
            TerminalParserWorker.InputAdmission.OVERLOADED, worker.requestPaste(text.toString()));
        assertTrue(output.snapshotWrites().isEmpty());
        assertEquals(0, worker.getPendingInputBytes());
        assertEquals(0, worker.getPendingInputCommandCount());
    }

    public void testInputCommandBudgetIsFiniteAndAbortReleasesCharges() {
        byte[] one = utf8("k");
        for (int i = 0; i < TerminalParserWorker.MAX_PENDING_INPUT_COMMANDS; i++) {
            assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
                worker.requestWrite(one, 0, 1));
        }
        assertEquals("one more command than the bound must be rejected",
            TerminalParserWorker.InputAdmission.OVERLOADED, worker.requestWrite(one, 0, 1));
        assertEquals(TerminalParserWorker.MAX_PENDING_INPUT_COMMANDS,
            worker.getPendingInputCommandCount());
        assertEquals(TerminalParserWorker.MAX_PENDING_INPUT_COMMANDS,
            worker.getPendingInputBytes());

        worker.abort();
        assertEquals("abort must release every pending input charge", 0,
            worker.getPendingInputBytes());
        assertEquals(0, worker.getPendingInputCommandCount());
        assertEquals("a stopping worker must reject new input",
            TerminalParserWorker.InputAdmission.CLOSED, worker.requestWrite(one, 0, 1));
    }

    public void testStoppedWorkerRejectsNewInputWithoutQueueing() throws Exception {
        startWorker();
        worker.stop();
        assertTrue(worker.awaitStopped(5000));

        assertEquals(TerminalParserWorker.InputAdmission.CLOSED,
            worker.requestWrite(utf8("k"), 0, 1));
        assertEquals(TerminalParserWorker.InputAdmission.CLOSED, worker.requestPaste("text"));
        assertEquals(TerminalParserWorker.InputAdmission.CLOSED,
            worker.requestSendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 1, 1, true));
        assertTrue(output.snapshotWrites().isEmpty());
    }

    public void testMouseReportSharesInputOrderAndCharge() throws Exception {
        enableSgrMouse();
        startWorker();

        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
            worker.requestWrite(utf8("K"), 0, 1));
        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
            worker.requestSendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 4, true));
        assertEquals(TerminalParserWorker.InputAdmission.ACCEPTED,
            worker.requestWrite(utf8("L"), 0, 1));
        awaitAllInputProcessed();

        assertEquals(Arrays.asList("K", "\033[<0;3;4M", "L"), output.snapshotWrites());
        assertInputBudgetReleased();
    }
}
