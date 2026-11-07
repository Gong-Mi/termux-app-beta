package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

/**
 * Audit L2-1: switching to an already-initialized idle session left the view black until the
 * session produced new output. The freshly attached view mailbox stayed empty and no
 * "request the current frame" mechanism existed; onDraw falls back to drawing the black
 * background when no frame is available.
 *
 * Contract pinned here: attaching a new frame sink to an initialized session must publish
 * the current screen to that sink, without any new PTY output.
 *
 * The production path is driven end to end (real emulator + real worker thread + real
 * session route wrapping); only the PTY-less initialization is simulated by wiring the
 * session's worker field directly.
 */
public class SessionAttachFramePublishTest extends TestCase {

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

    private static final class RecordingSink implements TerminalFrameSink {
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

    private static void writeBytes(ByteQueue queue, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue("ByteQueue write must succeed", queue.write(bytes, 0, bytes.length));
    }

    private static void setParserWorker(TerminalSession session, TerminalParserWorker worker) throws Exception {
        Field f = TerminalSession.class.getDeclaredField("mParserWorker");
        f.setAccessible(true);
        f.set(session, worker);
    }

    public void testAttachingASinkPublishesTheCurrentScreen() throws Exception {
        ByteQueue queue = new ByteQueue(64 * 1024);
        SilentClient client = new SilentClient();
        TerminalEmulator emulator = new TerminalEmulator(
            new NoopOutput(), COLUMNS, ROWS, CELL_WIDTH, CELL_HEIGHT, null, client);
        RecordingSink initialSink = new RecordingSink();
        TerminalParserWorker worker = new TerminalParserWorker(
            emulator, queue, initialSink, client, null, RECEIVE_BUFFER_SIZE, MAX_BYTES_PER_BATCH);
        worker.start();
        try {
            // The session produced output earlier; the initial publish lands on the old route.
            writeBytes(queue, "idle-session content");
            worker.requestAppend();
            for (int i = 0; i < 100 && initialSink.size() == 0; i++) Thread.sleep(50);
            assertEquals("initial publish should reach the first sink", 1, initialSink.size());

            TerminalSession session = new TerminalSession(
                "/unused", "/unused", new String[0], new String[0], null, client);
            setParserWorker(session, worker);

            // The user switches back to this idle session: a new consumer attaches while the
            // session has no pending output.
            RecordingSink attachedSink = new RecordingSink();
            session.setFrameSink(attachedSink);

            for (int i = 0; i < 100 && attachedSink.size() == 0; i++) Thread.sleep(50);
            assertTrue("attach must publish the current screen without new output (audit L2-1)",
                attachedSink.size() >= 1);
            TerminalModelFrame frame = attachedSink.last();
            assertNotNull(frame);
            assertTrue("republished frame must carry the current screen, was: '"
                    + frame.screen.getTranscriptText() + "'",
                frame.screen.getTranscriptText().startsWith("idle-session content"));
        } finally {
            worker.stop();
            worker.awaitStopped(5000);
        }
    }
}
