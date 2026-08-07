package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Perf-only benchmark (run by perf.yml with TERMUX_TEST_PERF_MODE=1).
 *
 * The functional unit tests feed inputs of a few dozen bytes, which cannot
 * resolve hot-path changes (WcWidth lookup, append chunking, scroll block
 * moves) against CI jitter. This class feeds MB-scale bursts through
 * {@link TerminalEmulator#append} at the same grain the real
 * TerminalSession.MainThreadHandler drain loop uses (ByteQueue batches of
 * up to 32KB), so those paths dominate the wall clock.
 *
 * The JUnit per-test wall time IS the measurement; perf_extract_timing.py
 * picks it up from the XML report. Payload construction happens before the
 * timed section. Nothing here asserts output content — perf.yml is the
 * only consumer.
 */
public class PerfBenchmarkTest extends TerminalTestCase {

    private static final int MB = 1024 * 1024;
    /** Matches the 32KB drain granularity of TerminalSession.MainThreadHandler. */
    private static final int FEED_CHUNK = 32 * 1024;

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
        // append() has no offset variant; stage each chunk (constant cost,
        // identical across builds under comparison).
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
}
