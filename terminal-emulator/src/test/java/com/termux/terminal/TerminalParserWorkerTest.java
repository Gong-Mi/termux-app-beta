package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.TestCase;

/**
 * Unit tests for {@link TerminalParserWorker} command-queue reliability and threading model.
 */
public class TerminalParserWorkerTest extends TestCase {

    private static final int COLUMNS = 80;
    private static final int ROWS = 24;
    private static final int CELL_WIDTH = 13;
    private static final int CELL_HEIGHT = 15;
    private static final int RECEIVE_BUFFER_SIZE = 4096;
    private static final int MAX_BYTES_PER_BATCH = 4096;

    private static final class NoopOutput extends TerminalOutput {
        @Override
        public void write(byte[] data, int offset, int count) { }
        @Override
        public void titleChanged(String oldTitle, String newTitle) { }
        @Override
        public void onCopyTextToClipboard(String text) { }
        @Override
        public void onPasteTextFromClipboard() { }
        @Override
        public void onBell() { }
        @Override
        public void onColorsChanged() { }
    }

    private static final class CapturingClient implements TerminalSessionClient {
        final AtomicInteger textChangedCount = new AtomicInteger(0);
        final AtomicInteger finishedCount = new AtomicInteger(0);
        volatile String lastCallbackThread;
        volatile CountDownLatch textLatch = new CountDownLatch(1);
        volatile CountDownLatch finishLatch = new CountDownLatch(1);

        @Override
        public void onTextChanged(@NonNull TerminalSession changedSession) {
            lastCallbackThread = Thread.currentThread().getName();
            textChangedCount.incrementAndGet();
            textLatch.countDown();
        }

        @Override
        public void onSessionFinished(@NonNull TerminalSession finishedSession) {
            lastCallbackThread = Thread.currentThread().getName();
            finishedCount.incrementAndGet();
            finishLatch.countDown();
        }

        @Override public void onTitleChanged(@NonNull TerminalSession changedSession) { }
        @Override public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) { }
        @Override public void onPasteTextFromClipboard(@Nullable TerminalSession session) { }
        @Override public void onBell(@NonNull TerminalSession session) { }
        @Override public void onColorsChanged(@NonNull TerminalSession session) { }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(@NonNull TerminalSession session, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) { }
        @Override public void logWarn(String tag, String message) { }
        @Override public void logInfo(String tag, String message) { }
        @Override public void logDebug(String tag, String message) { }
        @Override public void logVerbose(String tag, String message) { }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        @Override public void logStackTrace(String tag, Exception e) { }
    }

    private static final class CapturingSink implements TerminalFrameSink {
        final List<TerminalModelFrame> frames = new ArrayList<>();

        @Override
        public void publishFrame(TerminalModelFrame frame) {
            synchronized (frames) {
                frames.add(frame);
            }
        }

        int size() {
            synchronized (frames) { return frames.size(); }
        }

        TerminalModelFrame last() {
            synchronized (frames) {
                return frames.isEmpty() ? null : frames.get(frames.size() - 1);
            }
        }
    }

    /** Sink that can refuse snapshots, used to test latest-only coalescing in the worker. */
    private static final class GatedSink implements TerminalFrameSink {
        final List<TerminalModelFrame> frames = new ArrayList<>();
        volatile boolean allowSnapshot = true;

        @Override
        public void publishFrame(TerminalModelFrame frame) {
            synchronized (frames) {
                frames.add(frame);
            }
        }

        @Override
        public boolean shouldCaptureSnapshot() {
            return allowSnapshot;
        }

        int size() {
            synchronized (frames) { return frames.size(); }
        }

        TerminalModelFrame get(int index) {
            synchronized (frames) { return frames.get(index); }
        }
    }

    private static class WorkerHarness {
        final TerminalParserWorker worker;
        final ByteQueue inputQueue;
        final CapturingClient client;
        final CapturingSink sink;

        WorkerHarness(int maxBytesPerBatch) {
            client = new CapturingClient();
            sink = new CapturingSink();
            inputQueue = new ByteQueue(64 * 1024);
            TerminalEmulator emulator = new TerminalEmulator(
                    new NoopOutput(), COLUMNS, ROWS, CELL_WIDTH, CELL_HEIGHT, null, client);
            worker = new TerminalParserWorker(
                    emulator, inputQueue, sink, client, null, RECEIVE_BUFFER_SIZE, maxBytesPerBatch);
        }
    }

    private void writeString(ByteQueue queue, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(queue.write(bytes, 0, bytes.length));
    }

    private void waitForText(CapturingClient client) throws InterruptedException {
        assertTrue("Timed out waiting for onTextChanged",
                client.textLatch.await(5, TimeUnit.SECONDS));
    }

    private void waitForFinish(CapturingClient client) throws InterruptedException {
        assertTrue("Timed out waiting for onSessionFinished",
                client.finishLatch.await(5, TimeUnit.SECONDS));
    }

    public void testAppendProducesFrameAndRevisionIncreases() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        h.worker.start();
        try {
            writeString(h.inputQueue, "hello");
            h.worker.requestAppend();
            waitForText(h.client);

            TerminalModelFrame first = h.sink.last();
            assertNotNull(first);
            assertTrue("Screen should contain 'hello'", first.screen.getTranscriptText().contains("hello"));
            assertEquals("Raw worker callback must run on parser thread",
                    "TermSessionParserWorker", h.client.lastCallbackThread);
            long firstRevision = first.screenRevision;
            assertTrue("Revision should be positive", firstRevision > 0);

            h.client.textLatch = new CountDownLatch(1);
            writeString(h.inputQueue, "world");
            h.worker.requestAppend();
            waitForText(h.client);

            TerminalModelFrame second = h.sink.last();
            assertNotNull(second);
            assertTrue(second.screen.getTranscriptText().contains("world"));
            assertTrue("Revision must increase monotonically",
                    second.screenRevision > firstRevision);
            assertTrue("Earlier frame must remain an immutable snapshot",
                    first.screen.getTranscriptText().contains("hello"));
            assertFalse("Earlier frame must not observe later parser mutation",
                    first.screen.getTranscriptText().contains("world"));
            assertNotSame("Changed row must be copied into the new snapshot",
                    first.screen.rowAtExternal(0), second.screen.rowAtExternal(0));
            assertSame("Unchanged rows should be shared between immutable snapshots",
                    first.screen.rowAtExternal(10), second.screen.rowAtExternal(10));
            TerminalParserMetrics.Snapshot metrics = h.worker.getMetricsSnapshot();
            assertEquals(10, metrics.inputBytes);
            assertEquals(2, metrics.appendCommands);
            assertTrue("Each append should publish a model frame", metrics.publishedFrames >= 2);
        } finally {
            h.worker.stop();
        }
    }

    public void testAppendReschedulesWhenBudgetExhausted() throws Exception {
        WorkerHarness h = new WorkerHarness(64);
        h.worker.start();
        try {
            int length = 1024;
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) sb.append('x');
            String payload = sb.toString();
            writeString(h.inputQueue, payload);
            h.worker.requestAppend();

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (!h.inputQueue.hasData() && h.sink.size() > 0) {
                    Thread.sleep(100);
                    if (!h.inputQueue.hasData()) break;
                }
                Thread.sleep(50);
            }

            assertFalse("Input queue should be drained", h.inputQueue.hasData());
            assertTrue("Should have produced multiple frames", h.sink.size() > 1);
            TerminalModelFrame last = h.sink.last();
            assertNotNull(last);
            String text = last.screen.getTranscriptText();
            // Only the visible screen capacity can be observed from a single frame.
            assertEquals("Visible screen should be filled",
                    COLUMNS * ROWS, text.length());
        } finally {
            h.worker.stop();
        }
    }

    public void testRoutesCanBeReplacedAfterWorkerStart() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        CapturingSink replacementSink = new CapturingSink();
        CapturingClient replacementClient = new CapturingClient();
        h.worker.start();
        try {
            h.worker.setFrameSink(replacementSink);
            h.worker.setClient(replacementClient);
            writeString(h.inputQueue, "reattach");
            h.worker.requestAppend();
            waitForText(replacementClient);

            assertEquals("New sink must receive frames after replacement", 1, replacementSink.size());
            assertEquals("Old sink must not receive frames after replacement", 0, h.sink.size());
            assertEquals("New client must receive callbacks after replacement",
                    "TermSessionParserWorker", replacementClient.lastCallbackThread);
            assertEquals(0, h.client.textChangedCount.get());
        } finally {
            h.worker.stop();
            assertTrue(h.worker.awaitStopped(5000));
        }
    }

    public void testViewportRequestsCoalesceBeforeWorkerRuns() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < ROWS * 3; i++) lines.append("line-").append(i).append('\n');
        writeString(h.inputQueue, lines.toString());
        h.worker.requestAppend();
        for (int i = 0; i < 1000; i++) h.worker.requestViewport(-i);

        h.worker.start();
        try {
            assertTrue("Initial append should publish a frame",
                    h.client.textLatch.await(5, TimeUnit.SECONDS));
            long deadline = System.currentTimeMillis() + 5000;
            while (h.worker.getMetricsSnapshot().controlCommands < 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            TerminalParserMetrics.Snapshot metrics = h.worker.getMetricsSnapshot();
            assertEquals("Repeated pending viewport requests should collapse to one command",
                    1, metrics.controlCommands);
        } finally {
            h.worker.stop();
            assertTrue(h.worker.awaitStopped(5000));
        }
    }

    public void testViewportIsClampedToCurrentTranscriptRange() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        h.worker.start();
        try {
            // Out-of-range viewport on an empty transcript clamps to the current
            // viewport (0); the dedupe guard collapses it into the next append
            // frame instead of publishing a redundant identical snapshot.
            h.worker.requestViewport(-100);
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < ROWS * 3; i++) lines.append("line-").append(i).append('\n');
            writeString(h.inputQueue, lines.toString());
            h.worker.requestAppend();
            assertTrue("Transcript input should publish a frame",
                    h.client.textLatch.await(5, TimeUnit.SECONDS));
            TerminalModelFrame initial = h.sink.last();
            assertNotNull(initial);
            assertEquals("Empty transcript must clamp viewport to the screen",
                    0, initial.topRow);
            assertTrue(initial.topRow >= -initial.activeTranscriptRows);

            h.client.textLatch = new CountDownLatch(1);
            h.worker.requestViewport(-10000);
            assertTrue("Clamped transcript viewport should publish a frame",
                    h.client.textLatch.await(5, TimeUnit.SECONDS));
            TerminalModelFrame scrolled = h.sink.last();
            assertNotNull(scrolled);
            assertTrue("Published viewport must be valid for its captured transcript",
                    scrolled.topRow >= -scrolled.activeTranscriptRows);
            assertTrue("Viewport must never move below the transcript start",
                    scrolled.topRow <= 0);
            assertTrue("A real viewport change must actually move the frame",
                    scrolled.topRow < initial.topRow);

            // Moving back to the original viewport is a real change and publishes.
            h.client.textLatch = new CountDownLatch(1);
            h.worker.requestViewport(0);
            assertTrue("Returning to the original viewport should publish a frame",
                    h.client.textLatch.await(5, TimeUnit.SECONDS));
            assertEquals("Frame at restored viewport must be at the screen top", 0,
                    h.sink.last().topRow);
        } finally {
            h.worker.stop();
            assertTrue(h.worker.awaitStopped(5000));
        }
    }

    public void testVisibleControlMutationsPublishFrames() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        h.worker.start();
        try {
            h.worker.requestSetCursorBlinkState(false);
            h.worker.requestSetCursorBlinkingEnabled(false);
            h.worker.requestResetColors();
            long deadline = System.currentTimeMillis() + 5000;
            while (h.sink.size() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals("Each visible control mutation must publish a frame", 3, h.sink.size());
        } finally {
            h.worker.stop();
            assertTrue(h.worker.awaitStopped(5000));
        }
    }

    public void testInputControlsPublishFramesAfterWorkerMutation() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        h.worker.start();
        try {
            h.worker.requestPaste("paste-me");
            assertTrue("Paste must publish a post-mutation frame",
                    h.client.textLatch.await(5, TimeUnit.SECONDS));
            assertEquals(1, h.sink.size());

            h.client.textLatch = new CountDownLatch(1);
            h.worker.requestSendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 3, 4, true);
            assertTrue("Mouse input must publish a post-mutation frame",
                    h.client.textLatch.await(5, TimeUnit.SECONDS));
            assertEquals(2, h.sink.size());

            // A scroll-counter reset is only a mutation when the emulator has
            // an unread scroll amount; with no scrolling since the paste this
            // request is a semantic no-op and must NOT publish a frame (this
            // is the guard that breaks the view -> worker publish feedback
            // loop on idle sessions).
            h.worker.requestClearScrollCounter();
            Thread.sleep(200);
            assertEquals("Idle scroll-counter reset must not publish a frame", 2, h.sink.size());

            assertEquals("All input controls must be accounted for", 3,
                    h.worker.getMetricsSnapshot().controlCommands);
        } finally {
            h.worker.stop();
            assertTrue(h.worker.awaitStopped(5000));
        }
    }

    /** Regression: an idle session must not re-publish frames in response to the
     * view's repeated no-op viewport/scroll-counter sync calls (observed on-device
     * as publishedFrames doubling into the thousands with zero new input). */
    public void testIdleViewSyncDoesNotRunawayPublish() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        h.worker.start();
        try {
            writeString(h.inputQueue, "seed");
            h.worker.requestAppend();
            waitForText(h.client);
            long before = h.worker.getMetricsSnapshot().publishedFrames;
            assertTrue(before >= 1);

            // Mimic onScreenUpdated()'s per-notification sync: the view calls
            // these for every published frame even when nothing changed.
            for (int i = 0; i < 1000; i++) {
                h.worker.requestViewport(0);
                h.worker.requestClearScrollCounter();
            }
            Thread.sleep(300);

            long after = h.worker.getMetricsSnapshot().publishedFrames;
            assertTrue("No-op view sync must not publish frames (before=" + before
                    + " after=" + after + ")", after - before <= 2);
        } finally {
            h.worker.stop();
            assertTrue(h.worker.awaitStopped(5000));
        }
    }

    public void testStopRejectsFinishAndDuplicateStop() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        h.worker.start();
        h.worker.stop();
        h.worker.requestFinish(7);
        h.worker.stop();
        assertTrue(h.worker.awaitStopped(5000));
        assertEquals("Finish must not be queued after stop was requested", 0, h.client.finishedCount.get());
        assertEquals("Only one stop sentinel should be processed", 1,
                h.worker.getMetricsSnapshot().stopCommands);
    }

    public void testCommandOrderingResizeResetFinish() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        h.worker.start();
        try {
            writeString(h.inputQueue, "abc");
            h.worker.requestAppend();
            waitForText(h.client);
            assertTrue(h.sink.last().screen.getTranscriptText().contains("abc"));

            h.client.textLatch = new CountDownLatch(1);
            h.worker.requestResize(40, 12, CELL_WIDTH, CELL_HEIGHT);
            waitForText(h.client);
            assertEquals(40, h.sink.last().columns);
            assertEquals(12, h.sink.last().rows);

            h.client.textLatch = new CountDownLatch(1);
            h.worker.requestReset();
            waitForText(h.client);
            // TerminalEmulator.reset() resets modes/colors but does not erase screen content.
            assertTrue("Reset should produce a new frame", h.sink.last().screenRevision > 0);

            h.worker.requestFinish(0);
            waitForFinish(h.client);
            assertTrue("Finish text should be in screen",
                    h.sink.last().screen.getTranscriptText().contains("Process completed"));
        } finally {
            h.worker.stop();
        }
    }

    public void testFinishDrainsRemainingInput() throws Exception {
        WorkerHarness h = new WorkerHarness(64);
        h.worker.start();
        try {
            StringBuilder payload = new StringBuilder(4096);
            for (int i = 0; i < 4096; i++) payload.append('x');
            payload.append(" drain-me");
            writeString(h.inputQueue, payload.toString());
            h.worker.requestFinish(0);
            waitForFinish(h.client);

            assertTrue("Remaining input should be parsed before finish",
                    h.sink.last().screen.getTranscriptText().contains("drain-me"));
            assertTrue("Finish text should be present",
                    h.sink.last().screen.getTranscriptText().contains("Process completed"));
            TerminalParserMetrics.Snapshot metrics = h.worker.getMetricsSnapshot();
            assertEquals(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    metrics.inputBytes);
            assertEquals(1, metrics.finishCommands);
        } finally {
            h.worker.stop();
        }
    }

    public void testStopAfterRequestsDoesNotCrash() throws Exception {
        WorkerHarness h = new WorkerHarness(MAX_BYTES_PER_BATCH);
        h.worker.start();
        try {
            writeString(h.inputQueue, "x");
            h.worker.requestAppend();
            h.worker.requestPaste("ignored");
            h.worker.stop();
            assertTrue("Worker should stop cleanly", h.worker.awaitStopped(5000));
            assertEquals(1, h.worker.getMetricsSnapshot().stopCommands);
        } finally {
            h.worker.stop();
        }
    }

    public void testSnapshotSkippedWhenSinkCannotAcceptAndRepublishesOnConsumed() throws Exception {
        GatedSink sink = new GatedSink();
        CapturingClient client = new CapturingClient();
        ByteQueue queue = new ByteQueue(64 * 1024);
        TerminalEmulator emulator = new TerminalEmulator(
                new NoopOutput(), COLUMNS, ROWS, CELL_WIDTH, CELL_HEIGHT, null, client);
        TerminalParserWorker worker = new TerminalParserWorker(
                emulator, queue, sink, client, null, RECEIVE_BUFFER_SIZE, MAX_BYTES_PER_BATCH);
        worker.start();
        try {
            writeString(queue, "a");
            worker.requestAppend();
            for (int i = 0; i < 100 && sink.size() == 0; i++) {
                Thread.sleep(50);
            }
            assertEquals("first append should publish immediately", 1, sink.size());
            TerminalModelFrame first = sink.get(0);

            // Block snapshots. A second append should parse but not publish.
            sink.allowSnapshot = false;
            writeString(queue, "b");
            worker.requestAppend();
            for (int i = 0; i < 50 && sink.size() == 1; i++) {
                Thread.sleep(50);
            }
            assertEquals("worker should skip snapshot while sink cannot accept", 1, sink.size());

            // Notify worker that the previous frame was consumed. It should
            // republish with the latest state containing both characters.
            worker.onFrameConsumed(first);
            for (int i = 0; i < 100 && sink.size() == 1; i++) {
                Thread.sleep(50);
            }
            assertEquals("republish should produce a second frame", 2, sink.size());
            assertTrue("republished frame should contain merged state",
                    sink.get(1).screen.getTranscriptText().contains("ab"));
        } finally {
            worker.stop();
            assertTrue(worker.awaitStopped(5000));
        }
    }
}
