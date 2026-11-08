package com.termux.terminal;

import junit.framework.TestCase;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RED contract for paste emission atomicity on the parser-to-transport boundary.
 *
 * <p>The production defect is that {@link TerminalEmulator#paste(String)} emits
 * the bracketed prefix, the normalized body and the suffix as three separate
 * transport writes, so any writer that bypasses the parser queue can land
 * between them and corrupt the paste. The fix folds the whole paste into one
 * write call. This test only uses the pre-existing worker API
 * ({@code requestPaste}), so it compiles and fails on the unfixed source and
 * passes once the paste is emitted as one transport chunk.</p>
 *
 * <p>No PTY, no process, no Android framework: real worker + real emulator +
 * a capturing {@link TerminalOutput}.</p>
 */
public class TerminalParserWorkerPasteOrderedInputTest extends TestCase {

    private static final int COLUMNS = 40;
    private static final int ROWS = 6;

    private static final class CapturingOutput extends TerminalOutput {
        final List<String> writes = new ArrayList<>();
        final AtomicInteger writeCalls = new AtomicInteger();

        @Override
        public void write(byte[] data, int offset, int count) {
            writeCalls.incrementAndGet();
            synchronized (this) {
                writes.add(new String(data, offset, count, StandardCharsets.UTF_8));
            }
        }

        List<String> snapshotWrites() {
            synchronized (this) {
                return new ArrayList<>(writes);
            }
        }

        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private static final class Client {
        final AtomicInteger finished = new AtomicInteger();
        final AtomicReference<CountDownLatch> text = new AtomicReference<>(new CountDownLatch(1));

        final TerminalSessionClient proxy = (TerminalSessionClient) Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(),
            new Class<?>[] { TerminalSessionClient.class },
            (p, m, a) -> {
                if ("onTextChanged".equals(m.getName())) {
                    CountDownLatch latch = text.get();
                    if (latch != null) latch.countDown();
                }
                if ("onSessionFinished".equals(m.getName())) finished.incrementAndGet();
                return null;
            });

        void resetTextLatch() {
            text.set(new CountDownLatch(1));
        }

        boolean awaitText(long millis) throws InterruptedException {
            return text.get().await(millis, TimeUnit.MILLISECONDS);
        }
    }

    private CapturingOutput output;
    private TerminalEmulator emulator;
    private TerminalParserWorker worker;
    private Client client;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        output = new CapturingOutput();
        client = new Client();
        emulator = new TerminalEmulator(output, COLUMNS, ROWS, 8, 16, null, client.proxy);
        ByteQueue input = new ByteQueue(64 * 1024);
        worker = new TerminalParserWorker(emulator, input, frame -> { }, client.proxy, null, 4096, 32768);
    }

    private void startWorker() {
        worker.start();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            worker.stop();
            assertTrue("parser worker leaked", worker.awaitStopped(5000));
        } finally {
            super.tearDown();
        }
    }

    /**
     * Bracketed paste must be delivered as one atomic transport write. On the
     * unfixed source this observes three writes ("\033[200~", body, "\033[201~"),
     * which is exactly the window a UI key could slip into.
     */
    public void testBracketedPasteIsOneAtomicTransportWrite() throws Exception {
        byte[] enableBracketedPaste = "\033[?2004h".getBytes(StandardCharsets.UTF_8);
        emulator.append(enableBracketedPaste, enableBracketedPaste.length);
        assertTrue("fixture: bracketed paste must be enabled", emulator.isBracketedPasteModeEnabled());
        startWorker();

        worker.requestPaste("abc\r\ndef");
        assertTrue("paste should publish a frame", client.awaitText(5000));

        List<String> writes = output.snapshotWrites();
        assertEquals("bracketed paste must be a single transport write so no later key can be inserted; writes="
            + writes, 1, writes.size());
        assertEquals("\033[200~abc\rdef\033[201~", writes.get(0));
    }

    /** Control: with DECSET 2004 off the body is still one write and is unchanged. */
    public void testUnbracketedPasteIsOneBodyWrite() throws Exception {
        startWorker();

        worker.requestPaste("abc\r\ndef");
        assertTrue("paste should publish a frame", client.awaitText(5000));

        List<String> writes = output.snapshotWrites();
        assertEquals("unbracketed paste must be a single body write", 1, writes.size());
        assertEquals("abc\rdef", writes.get(0));
    }
}
