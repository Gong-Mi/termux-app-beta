package com.termux.terminal;

import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic contract tests for the async-migration audit of the installed source
 * (f6c74a84 / master d8abd5b7).
 *
 * <p>Five of these tests pin observed defects and are EXPECTED TO FAIL on the current
 * source. They are written in the shape the fixes must satisfy, so each follow-up fix
 * PR flips its own test from RED to GREEN. The three "*Control*" tests pass today and
 * prove this fixture can discriminate broken from intended behavior.
 *
 * <p>Reproduce:
 * <pre>
 * ./gradlew :terminal-emulator:testDebugUnitTest \
 *     --tests "com.termux.terminal.AsyncMigrationContractTest"
 * </pre>
 * Expected on the current source: {@code Tests run: 8, Failures: 5}.
 *
 * <p>Evidence boundary: production terminal-emulator classes with isolated Android
 * Handler/Looper shims (unitTests.returnDefaultValues = true). No ART, no PTY
 * subprocess, no device, no GPU.
 */
public class AsyncMigrationContractTest extends TestCase {

    private static final int COLUMNS = 40;

    private interface Condition {
        boolean isTrue();
    }

    private static void waitUntil(String what, Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.isTrue() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue("timed out waiting for: " + what, condition.isTrue());
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class NoopOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    private static TerminalSessionClient shimClient(final Integer cursorStyle, final AtomicInteger finishedCounter) {
        return (TerminalSessionClient) Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(),
            new Class<?>[] { TerminalSessionClient.class },
            (proxy, method, args) -> {
                if ("getTerminalCursorStyle".equals(method.getName())) return cursorStyle;
                if ("onSessionFinished".equals(method.getName()) && finishedCounter != null) {
                    finishedCounter.incrementAndGet();
                }
                return null;
            });
    }

    /** Sink that always accepts and records the latest frame; optionally blocks inside one publish. */
    private static final class RecordingSink implements TerminalFrameSink {
        final AtomicReference<TerminalModelFrame> latest = new AtomicReference<>();
        volatile int blockOnScrollCounter = Integer.MIN_VALUE;
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);

        @Override public void publishFrame(TerminalModelFrame frame) {
            latest.set(frame);
            if (frame.scrollCounter == blockOnScrollCounter) {
                arrived.countDown();
                try {
                    released.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override public boolean shouldCaptureSnapshot() { return true; }
    }

    /** Sink that models an attached view mailbox holding one unconsumed frame. */
    private static final class HoldingSink implements TerminalFrameSink {
        final AtomicReference<TerminalModelFrame> latest = new AtomicReference<>();

        @Override public void publishFrame(TerminalModelFrame frame) { latest.set(frame); }

        @Override public boolean shouldCaptureSnapshot() { return latest.get() == null; }
    }

    private static final class Harness {
        final TerminalEmulator emulator;
        final ByteQueue incoming = new ByteQueue(64 * 1024);
        final AtomicInteger finished = new AtomicInteger();
        final TerminalParserWorker worker;
        final Thread workerThread;

        Harness(TerminalFrameSink sink, Integer cursorStyle, int rows) throws Exception {
            TerminalSessionClient client = shimClient(cursorStyle, finished);
            emulator = new TerminalEmulator(new NoopOutput(), COLUMNS, rows, 8, 16, null, client);
            worker = new TerminalParserWorker(emulator, incoming, sink, client, null, 4096, 32768);
            workerThread = (Thread) readField(worker, "mThread");
        }

        void seedScrollableHistory() {
            StringBuilder seed = new StringBuilder();
            for (int i = 0; i < 18; i++) seed.append("line").append(i).append("\r\n");
            byte[] bytes = seed.toString().getBytes(StandardCharsets.UTF_8);
            emulator.append(bytes, bytes.length);
            emulator.clearScrollCounter();
        }

        void appendAndRequest(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            assertTrue("input bytes rejected", incoming.write(bytes, 0, bytes.length));
            worker.requestAppend();
        }

        void close() throws Exception {
            incoming.close();
            worker.stop();
            worker.awaitStopped(3000);
        }
    }

    // ------------------------------------------------------------------
    // Scroll-counter delivery: TerminalView reads the counter from the latest
    // frame and then asks the worker to clear it. The clear is now asynchronous,
    // so the same scroll can be applied twice, or a new scroll can be wiped
    // before the UI ever reads it. These tests pin the exactly-once contract.
    // (The counter is only read while selecting text or auto-scroll is disabled:
    // TerminalView.onScreenUpdated, installed source lines 542-575.)
    // ------------------------------------------------------------------

    /** Clear lags behind new output: pass 1 reads 1, pass 2 still sees cumulative 2. */
    public void testScrollCounterAppliedExactlyOnceWhenClearLagsBehindNewOutput() throws Exception {
        RecordingSink sink = new RecordingSink();
        Harness h = new Harness(sink, null, 4);
        h.seedScrollableHistory();
        h.worker.start();
        try {
            h.appendAndRequest("A\r\n");
            waitUntil("first scroll frame", () -> sink.latest.get() != null && sink.latest.get().scrollCounter == 1);
            int appliedByUi = sink.latest.get().scrollCounter; // UI pass #1: apply 1

            sink.blockOnScrollCounter = 2;
            h.appendAndRequest("B\r\n"); // worker appends B and blocks inside its publish
            assertTrue("fixture: worker must reach the second publish", sink.arrived.await(5, TimeUnit.SECONDS));
            h.worker.requestClearScrollCounter(); // queued behind the blocked second append

            // UI pass #2 runs before the queued clear: it still reads the cumulative counter.
            appliedByUi += sink.latest.get().scrollCounter;
            sink.released.countDown();

            waitUntil("clear processed", () -> h.emulator.getScrollCounter() == 0);
            assertEquals(
                "scroll counter must be applied exactly once; UI applied " + appliedByUi
                    + " while only 2 scrolls happened (cumulative counter read again before the queued clear ran)",
                2, appliedByUi);
        } finally {
            sink.released.countDown();
            h.close();
        }
    }

    /** Clear lands before the next UI read: the counter is wiped with an unapplied scroll. */
    public void testScrollCounterAppliedExactlyOnceWhenClearProcessesBeforeNextUiRead() throws Exception {
        RecordingSink sink = new RecordingSink();
        Harness h = new Harness(sink, null, 4);
        h.seedScrollableHistory();
        h.worker.start();
        try {
            h.appendAndRequest("A\r\n");
            waitUntil("first scroll frame", () -> sink.latest.get() != null && sink.latest.get().scrollCounter == 1);
            int appliedByUi = sink.latest.get().scrollCounter; // UI pass #1: apply 1

            h.appendAndRequest("B\r\n"); // B's scroll lands before the clear
            h.worker.requestClearScrollCounter(); // clears both counters before the UI reads again
            waitUntil("clear processed and zero frame published",
                () -> h.emulator.getScrollCounter() == 0
                    && sink.latest.get() != null && sink.latest.get().scrollCounter == 0);

            appliedByUi += sink.latest.get().scrollCounter; // UI pass #2 reads 0: B's scroll was wiped
            assertEquals(
                "scroll counter must be applied exactly once; UI applied " + appliedByUi
                    + " while 2 scrolls happened (one scroll was cleared before any frame carried it to the UI)",
                2, appliedByUi);
        } finally {
            h.close();
        }
    }

    /** Control: sequential apply/clear/apply is exact once on current code. */
    public void testScrollCounterControlSequentialIsExactOnce() throws Exception {
        RecordingSink sink = new RecordingSink();
        Harness h = new Harness(sink, null, 4);
        h.seedScrollableHistory();
        h.worker.start();
        try {
            h.appendAndRequest("A\r\n");
            waitUntil("first scroll frame", () -> sink.latest.get() != null && sink.latest.get().scrollCounter == 1);
            int applied = sink.latest.get().scrollCounter;

            h.worker.requestClearScrollCounter();
            waitUntil("clear processed", () -> h.emulator.getScrollCounter() == 0);

            h.appendAndRequest("B\r\n");
            waitUntil("second scroll frame", () -> sink.latest.get() != null && sink.latest.get().scrollCounter == 1);
            applied += sink.latest.get().scrollCounter;

            assertEquals("control: sequential apply/clear is exact once", 2, applied);
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // FINISH terminal frames: the completion banner (and any drained tail output)
    // is published through the ordinary snapshot gate. If the mailbox still holds
    // an unconsumed frame, the final frame is skipped, the worker stops, and the
    // late ACK's republish is stranded in a dead queue.
    // (TerminalParserWorker.processFinish / publishFrame, installed lines 323-358.)
    // ------------------------------------------------------------------

    public void testCompletionBannerReachesConsumerWhenMailboxBusy() throws Exception {
        HoldingSink sink = new HoldingSink();
        Harness h = new Harness(sink, null, 24);
        h.worker.start();
        try {
            h.appendAndRequest("seed");
            waitUntil("initial frame", () -> sink.latest.get() != null);
            TerminalModelFrame pending = sink.latest.get();

            h.worker.requestFinish(7);
            waitUntil("finish callback", () -> h.finished.get() == 1);
            assertTrue("worker must stop after finish", h.worker.awaitStopped(3000));

            h.worker.onFrameConsumed(pending); // late ACK after the worker already stopped
            int stranded = ((BlockingQueue<?>) readField(h.worker, "mCommandQueue")).size();

            String visible = sink.latest.get().screen.getTranscriptText();
            assertTrue(
                "completion banner must reach the consumer even when the mailbox held an unconsumed frame"
                    + " (finish_callback=" + h.finished.get() + ", stranded_queue=" + stranded + ")",
                visible.contains("Process completed"));
        } finally {
            h.close();
        }
    }

    /** Control: idle mailbox still delivers the banner on current code. */
    public void testCompletionBannerReachesConsumerWhenMailboxIdle() throws Exception {
        RecordingSink sink = new RecordingSink();
        Harness h = new Harness(sink, null, 24);
        h.worker.start();
        try {
            h.appendAndRequest("seed");
            waitUntil("initial frame", () -> sink.latest.get() != null);

            h.worker.requestFinish(0);
            waitUntil("finish callback", () -> h.finished.get() == 1);
            assertTrue("worker must stop after finish", h.worker.awaitStopped(3000));

            assertTrue("control: idle mailbox receives the completion banner",
                sink.latest.get().screen.getTranscriptText().contains("Process completed"));
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // Cursor state: Session.isCursorEnabled() must follow the model's DECSET
    // enabled state, not the blink phase or the cursor style.
    // (TerminalSession.isCursorEnabled, installed lines 573-580.)
    // ------------------------------------------------------------------

    private static boolean sessionGetterEnabled(TerminalEmulator emulator, TerminalSessionClient client) throws Exception {
        TerminalSession session = new TerminalSession("/unused", "/unused", new String[0], new String[0], null, client);
        session.mEmulator = emulator;
        TerminalModelFrame frame = new TerminalModelFrame(emulator, 0, null, 0);
        Field field = TerminalSession.class.getDeclaredField("mLatestFrame");
        field.setAccessible(true);
        field.set(session, frame);
        return session.isCursorEnabled();
    }

    public void testCursorEnabledFollowsModelWhenBlinkPhaseInvisible() throws Exception {
        TerminalSessionClient client = shimClient(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK, null);
        TerminalEmulator emulator = new TerminalEmulator(new NoopOutput(), COLUMNS, 4, 8, 16, null, client);
        emulator.setCursorBlinkingEnabled(true);
        emulator.setCursorBlinkState(false); // blink phase currently hidden

        boolean modelEnabled = emulator.isCursorEnabled();
        boolean sessionGetter = sessionGetterEnabled(emulator, client);
        assertTrue("fixture: cursor must still be enabled while blink phase is hidden", modelEnabled);
        assertEquals(
            "Session.isCursorEnabled must not conflate blink visibility with the DECSET enabled state"
                + " (block cursor, blink phase hidden)",
            modelEnabled, sessionGetter);
    }

    public void testCursorEnabledFollowsModelWhenHiddenWithNonBlockStyle() throws Exception {
        TerminalSessionClient client = shimClient(TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE, null);
        TerminalEmulator emulator = new TerminalEmulator(new NoopOutput(), COLUMNS, 4, 8, 16, null, client);
        byte[] hide = "\033[?25l".getBytes(StandardCharsets.UTF_8);
        emulator.append(hide, hide.length); // DECSET 25l hides the cursor

        boolean modelEnabled = emulator.isCursorEnabled();
        boolean sessionGetter = sessionGetterEnabled(emulator, client);
        assertFalse("fixture: cursor hidden by DECSET 25l", modelEnabled);
        assertEquals(
            "Session.isCursorEnabled must be false for a hidden cursor even when the style is nonzero",
            modelEnabled, sessionGetter);
    }

    /** Control: visible default cursor agrees on both paths. */
    public void testCursorEnabledControlVisibleCursor() throws Exception {
        TerminalSessionClient client = shimClient(TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK, null);
        TerminalEmulator emulator = new TerminalEmulator(new NoopOutput(), COLUMNS, 4, 8, 16, null, client);
        emulator.setCursorBlinkState(true);

        boolean modelEnabled = emulator.isCursorEnabled();
        boolean sessionGetter = sessionGetterEnabled(emulator, client);
        assertEquals("control: visible cursor agrees on both paths", modelEnabled, sessionGetter);
    }
}
