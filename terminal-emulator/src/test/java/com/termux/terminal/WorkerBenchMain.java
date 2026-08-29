// Repeatable worker/snapshot/mailbox benchmark with fixed iteration count.
//
// Motivation: PerfBenchmarkTest runs each benchmark once inside JUnit, whose
// wall-clock grain (~1s suite, single sample) cannot resolve the ~6% delta the
// PR-vs-master comparison flagged on PerfBenchmarkTest. This main-class
// harness repeats the SAME production-shaped path (ByteQueue ->
// TerminalParserWorker -> immutable TerminalModelFrame snapshot ->
// latest-only sink) for N iterations and reports median + IQR so a real
// worker-path regression becomes distinguishable from runner jitter.
//
// Run:
//   ./gradlew :terminal-emulator:testDebugUnitTest --tests ...   (existing CI gate, unchanged)
//   Manual/JMH-style (this class):
//     java -cp <test-runtime-cp> com.termux.terminal.WorkerBenchMain [iterations] [warmup]
package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone, repeatable measurement of the production parser-worker pipeline.
 * Deliberately mirrors PerfBenchmarkTest.feedWorkerTimed() workload shape
 * (same chunk size, same 4 MiB ASCII/CJK payloads, same latest-only sink) but
 * loops it and reports robust statistics instead of one JUnit sample.
 */
public final class WorkerBenchMain {

    private static final int MB = 1024 * 1024;
    private static final int FEED_CHUNK = 32 * 1024;

    private static final class NoopClient implements TerminalSessionClient {
        @Override public void onTextChanged(TerminalSession session) { }
        @Override public void onSessionFinished(TerminalSession session) { }
        @Override public void onTitleChanged(TerminalSession changedSession) { }
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) { }
        @Override public void onPasteTextFromClipboard(TerminalSession session) { }
        @Override public void onBell(TerminalSession session) { }
        @Override public void onColorsChanged(TerminalSession session) { }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(TerminalSession session, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) { }
        @Override public void logWarn(String tag, String message) { }
        @Override public void logInfo(String tag, String message) { }
        @Override public void logDebug(String tag, String message) { }
        @Override public void logVerbose(String tag, String message) { }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        @Override public void logStackTrace(String tag, Exception e) { }
    }

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

    /** One benchmark pass; returns elapsed nanoseconds for the whole payload. */
    private static long feedOnce(byte[] payload) throws Exception {
        NoopClient client = new NoopClient();
        TerminalEmulator emulator = new TerminalEmulator(
            new TerminalTestCase.MockTerminalOutput(), 80, 24, 8, 16, 48, client);
        ByteQueue queue = new ByteQueue(64 * 1024);
        LatestOnlySink sink = new LatestOnlySink();
        TerminalParserWorker worker = new TerminalParserWorker(
            emulator, queue, sink, client, null, FEED_CHUNK, FEED_CHUNK);
        worker.start();
        long start = System.nanoTime();
        try {
            for (int off = 0; off < payload.length; off += FEED_CHUNK) {
                int len = Math.min(FEED_CHUNK, payload.length - off);
                if (!queue.write(payload, off, len)) throw new AssertionError("queue closed");
                worker.requestAppend();
            }
            long deadline = System.currentTimeMillis() + 30_000;
            TerminalParserMetrics.Snapshot metrics;
            do {
                metrics = worker.getMetricsSnapshot();
                if (metrics.inputBytes >= payload.length) break;
                Thread.sleep(2);
            } while (System.currentTimeMillis() < deadline);
            if (worker.getMetricsSnapshot().inputBytes != payload.length) {
                throw new AssertionError("did not drain");
            }
        } finally {
            worker.stop();
            if (!worker.awaitStopped(10_000)) throw new AssertionError("worker did not stop");
        }
        return System.nanoTime() - start;
    }

    private static double median(long[] values) {
        Arrays.sort(values);
        int n = values.length;
        return n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2.0;
    }

    private static double q1(long[] values) {
        Arrays.sort(values);
        return values[(int) Math.floor(values.length * 0.25)];
    }

    private static double q3(long[] values) {
        Arrays.sort(values);
        return values[(int) Math.ceil(values.length * 0.75) - 1];
    }

    private static void bench(String label, byte[] payload, int warmup, int iters) throws Exception {
        for (int i = 0; i < warmup; i++) feedOnce(payload);
        long[] samples = new long[iters];
        long frames = 0, replaced = 0;
        for (int i = 0; i < iters; i++) {
            samples[i] = feedOnce(payload);
        }
        Arrays.sort(samples);
        double med = median(samples);
        double totalMb = payload.length / 1024.0 / 1024.0 * iters;
        System.out.printf("%s: iters=%d warmup=%d median=%.1fms q1=%.1f q3=%.1f "
                + "min=%.1f max=%.1f medianMB/s=%.1f%n",
            label, iters, warmup, med / 1e6, q1(samples) / 1e6, q3(samples) / 1e6,
            samples[0] / 1e6, samples[samples.length - 1] / 1e6,
            totalMb / (med * iters / 1e9));
    }

    public static void main(String[] args) throws Exception {
        int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int iters = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        byte[] ascii = buildPayload("a", 4 * MB);
        byte[] cjk = buildPayload("中文混排ＡＢＣ123", 4 * MB);
        bench("worker/ascii", ascii, warmup, iters);
        bench("worker/cjk  ", cjk, warmup, iters);
    }
}
