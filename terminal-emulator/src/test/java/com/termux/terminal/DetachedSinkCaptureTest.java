package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

/**
 * Pins the detached-sink handoff contract.
 *
 * When a session's view sink is detached (the user switched sessions), the worker must not
 * keep paying full snapshot cost for a session with no consumer. The cost gate is
 * {@link TerminalFrameSink#shouldCaptureSnapshot()}: the worker's {@code publishFrame()}
 * skips the screen snapshot and only marks dirty while the sink refuses capture — the same
 * gate the attached view's sink uses when its mailbox is occupied.
 *
 * The old {@link TerminalSession#detachFrameSink()} stub returned {@code true} (capture
 * every publish; frames then cached with no consumer), so every backgrounded session paid a
 * full screen copy per PTY batch. The fixed stub returns {@code false} and relies on the
 * worker's dirty-mark + {@code onFrameConsumed} republish semantics: parsing continues,
 * snapshots resume on re-attach (mailbox empty) or on the next consumed frame.
 */
public class DetachedSinkCaptureTest extends TestCase {

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

    private static final class SilentClient implements TerminalSessionClient {
        @Override
        public void onTextChanged(@NonNull TerminalSession changedSession) { }
        @Override
        public void onTitleChanged(@NonNull TerminalSession changedSession) { }
        @Override
        public void onSessionFinished(@NonNull TerminalSession finishedSession) { }
        @Override
        public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) { }
        @Override
        public void onPasteTextFromClipboard(@Nullable TerminalSession session) { }
        @Override
        public void onBell(@NonNull TerminalSession session) { }
        @Override
        public void onColorsChanged(@NonNull TerminalSession session) { }
        @Override
        public void onTerminalCursorStateChange(boolean state) { }
        @Override
        public void setTerminalShellPid(@NonNull TerminalSession session, int pid) { }
        @Override
        public Integer getTerminalCursorStyle() { return null; }
        @Override
        public void logError(String tag, String message) { }
        @Override
        public void logWarn(String tag, String message) { }
        @Override
        public void logInfo(String tag, String message) { }
        @Override
        public void logVerbose(String tag, String message) { }
        @Override
        public void logDebug(String tag, String message) { }
        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        @Override
        public void logStackTrace(String tag, Exception e) { }
    }

    /** Records every delivered frame; {@code allowSnapshot=false} mimics the detached stub. */
    private static final class RecordingSink implements TerminalFrameSink {
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
            synchronized (frames) {
                return frames.size();
            }
        }
    }

    private void writeBytes(ByteQueue queue, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue("ByteQueue write must succeed", queue.write(bytes, 0, bytes.length));
    }

    /**
     * A sink that refuses capture (the fixed detach stub's contract) must stop the worker
     * from snapshotting: a second append parses without delivering a new frame, and the
     * emulator state still advances so a later capture (re-attach / consumed callback)
     * sees the merged content.
     */
    public void testWorkerSkipsSnapshotWhenSinkRefusesCapture() throws Exception {
        RecordingSink sink = new RecordingSink();
        ByteQueue queue = new ByteQueue(64 * 1024);
        SilentClient client = new SilentClient();
        TerminalEmulator emulator = new TerminalEmulator(
                new NoopOutput(), COLUMNS, ROWS, CELL_WIDTH, CELL_HEIGHT, null, client);
        TerminalParserWorker worker = new TerminalParserWorker(
                emulator, queue, sink, client, null, RECEIVE_BUFFER_SIZE, MAX_BYTES_PER_BATCH);

        worker.start();
        try {
            writeBytes(queue, "a");
            worker.requestAppend();
            for (int i = 0; i < 100 && sink.size() == 0; i++) {
                Thread.sleep(50);
            }
            assertEquals("first append should publish while sink accepts capture", 1, sink.size());
            assertTrue("first frame must carry the first append",
                    sink.frames.get(0).screen.getTranscriptText().contains("a"));

            // The detached-sink contract: refuse eager capture.
            sink.allowSnapshot = false;
            writeBytes(queue, "b");
            worker.requestAppend();
            // Give any (contract-violating) extra publish ample time to land.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                if (sink.size() != 1) {
                    fail("worker must not snapshot while sink refuses capture; frames=" + sink.size());
                }
                Thread.sleep(50);
            }
            assertEquals("no new frame while capture refused", 1, sink.size());

            // Capture resumes once the sink accepts again (re-attach equivalent) and the
            // pending dirty state must produce a frame containing BOTH appends.
            sink.allowSnapshot = true;
            writeBytes(queue, "c");
            worker.requestAppend();
            for (int i = 0; i < 100 && sink.size() == 1; i++) {
                Thread.sleep(50);
            }
            assertEquals("capture resumes when sink accepts again", 2, sink.size());
            String merged = sink.frames.get(1).screen.getTranscriptText();
            assertTrue("resumed frame must contain all parsed content (a,b,c): " + merged,
                    merged.contains("a") && merged.contains("b") && merged.contains("c"));
        } finally {
            worker.stop();
            assertTrue(worker.awaitStopped(5000));
        }
    }
}
