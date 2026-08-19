package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Perf-only benchmark (run by perf.yml with TERMUX_TEST_PERF_MODE=1).
 *
 * The functional unit tests feed inputs of a few dozen bytes, which cannot
 * resolve hot-path changes (WcWidth lookup, append chunking, scroll block
 * moves) against CI jitter. This class feeds MB-scale bursts through
 * TerminalEmulator.append at the same grain the real TerminalSession input
 * reader uses, and also measures the production parser-worker/snapshot path.
 *
 * The JUnit per-test wall time IS the measurement; perf_extract_timing.py
 * picks it up from the XML report. Payload construction happens before the
 * timed section. Nothing here asserts output content — perf.yml is the only
 * consumer.
 */
public class PerfBenchmarkTest extends TerminalTestCase {

    private static final int MB = 1024 * 1024;
    /** Matches the 32KB drain granularity of TerminalSession input handling. */
    private static final int FEED_CHUNK = 32 * 1024;

    /** Minimal callback route required by TerminalParserWorker. */
    private static final class NoopClient implements TerminalSessionClient {
        @Override public void onTextChanged(@NonNull TerminalSession session) { }
        @Override public void onSessionFinished(@NonNull TerminalSession session) { }
        @Override public void onTitleChanged(@NonNull TerminalSession session) { }
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

    /** Latest-only sink: measures model publication and replacement before a consumer draw. */
    private static final class LatestOnlySink implements TerminalFrameSink {
        final AtomicReference<TerminalModelFrame> latest = new AtomicReference<>();
        final AtomicLong published = new AtomicLong();
        final AtomicLong replaced = new AtomicLong();

        @Override
        public void publishFrame(TerminalModelFrame frame) {
            published.incrementAndGet();
            if (latest.getAndSet(frame) != null) replaced.incrementAndGet();
        }
    }

    /** Build a payload of at least targetBytes by repeating unit, line-broken at ~79 cols. */
    private static byte[] buildPayload(String unit, int targetBytes) {
        byte[] u = unit.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(targetBytes);
        int col = 0;
        while (out.size() < targetBytes) {
            out.write(u, 0, u.length);
            col += u.length;
            if (col >= 79) {
                out.write('\r');
                out.write('\n');
                col = 0;
            }
        }
        return out.toByteArray();
    }

    /** Feed payload through the emulator in FEED_CHUNK pieces; returns elapsed ms. */
    private long feedTimed(byte[] payload) {
        byte[] chunk = new byte[FEED_CHUNK];
        long start = System.nanoTime();
        for (int off = 0; off < payload.length; off += FEED_CHUNK) {
            int len = Math.min(FEED_CHUNK, payload.length - off);
            System.arraycopy(payload, off, chunk, 0, len);
            mTerminal.append(chunk, len);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        double mbPerSec = payload.length / 1024.0 / 1024.0 / (elapsedMs / 1000.0);
        System.out.println(getName() + ": " + payload.length + " bytes in " + elapsedMs
            + " ms = " + String.format("%.1f", mbPerSec) + " MB/s");
        return elapsedMs;
    }

    /**
     * Feed through the real worker input rhythm and immutable snapshot path.
     * This includes queue handoff, parser scheduling, full screen snapshot copy,
     * frame publication, and latest-only replacement. It intentionally does not
     * claim to measure Canvas/RenderThread/GPU work.
     */
    private long feedWorkerTimed(byte[] payload) throws Exception {
        NoopClient client = new NoopClient();
        TerminalEmulator emulator = new TerminalEmulator(
            new MockTerminalOutput(), 80, 24, INITIAL_CELL_WIDTH_PIXELS,
            INITIAL_CELL_HEIGHT_PIXELS, 48, client);
        ByteQueue queue = new ByteQueue(64 * 1024);
        LatestOnlySink sink = new LatestOnlySink();
        TerminalParserWorker worker = new TerminalParserWorker(
            emulator, queue, sink, client, null, FEED_CHUNK, FEED_CHUNK);
        worker.start();
        long start = System.nanoTime();
        try {
            for (int off = 0; off < payload.length; off += FEED_CHUNK) {
                int len = Math.min(FEED_CHUNK, payload.length - off);
                if (!queue.write(payload, off, len)) throw new AssertionError("worker input queue closed");
                worker.requestAppend();
            }

            long deadline = System.currentTimeMillis() + 30_000;
            TerminalParserMetrics.Snapshot metrics;
            do {
                metrics = worker.getMetricsSnapshot();
                if (metrics.inputBytes >= payload.length) break;
                Thread.sleep(2);
            } while (System.currentTimeMillis() < deadline);
            metrics = worker.getMetricsSnapshot();
            if (metrics.inputBytes != payload.length) {
                throw new AssertionError("worker benchmark did not drain input: "
                    + metrics.inputBytes + "/" + payload.length);
            }
        } finally {
            worker.stop();
            if (!worker.awaitStopped(10_000)) throw new AssertionError("worker did not stop");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        TerminalParserMetrics.Snapshot metrics = worker.getMetricsSnapshot();
        double mbPerSec = payload.length / 1024.0 / 1024.0 / (elapsedMs / 1000.0);
        System.out.println(getName() + ": worker " + payload.length + " bytes in " + elapsedMs
            + " ms = " + String.format("%.1f", mbPerSec) + " MB/s"
            + " parserFrames=" + metrics.publishedFrames
            + " mailboxReplaced=" + sink.replaced.get());
        return elapsedMs;
    }

    /** Plain ASCII scroll storm: append + line wrap + scroll block moves. */
    public void testPerfAsciiBurst() {
        withTerminalSized(80, 24);
        feedTimed(buildPayload("a", 4 * MB));
    }

    /** CJK-dense burst: every code point hits WcWidth and double-width cell logic. */
    public void testPerfMixedCjkBurst() {
        withTerminalSized(80, 24);
        feedTimed(buildPayload("中文混排ＡＢＣ123", 4 * MB));
    }

    /** CSI color storm: escape-sequence state machine + style churn per cell. */
    public void testPerfCsiColorBurst() {
        withTerminalSized(80, 24);
        feedTimed(buildPayload("\033[31mX\033[0m\033[32mY\033[0m", 2 * MB));
    }

    /** End-to-end parser/snapshot/latest-only mailbox benchmark on ASCII input. */
    public void testPerfWorkerSnapshotMailboxAsciiBurst() throws Exception {
        feedWorkerTimed(buildPayload("a", 4 * MB));
    }

    /** End-to-end parser/snapshot/latest-only mailbox benchmark on mixed CJK input. */
    public void testPerfWorkerSnapshotMailboxMixedCjkBurst() throws Exception {
        feedWorkerTimed(buildPayload("中文混排ＡＢＣ123", 4 * MB));
    }
}
