// Temporary profile harness: runs the CJK append hot path for a few seconds
// so a JFR recording gets enough ExecutionSample events to be actionable.
package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class PerfProfileMain {

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

    public static void main(String[] args) {
        TerminalEmulator emulator = new TerminalEmulator(
                new TerminalTestCase.MockTerminalOutput(), 80, 24, 8, 16, 48, null);
        byte[] payload = buildPayload("中文混排ＡＢＣ123", 4 * 1024 * 1024);
        byte[] chunk = new byte[32 * 1024];
        long deadline = System.nanoTime() + (args.length > 0 ? Long.parseLong(args[0]) : 6000) * 1_000_000L;
        long loops = 0;
        long bytes = 0;
        long start = System.nanoTime();
        while (System.nanoTime() < deadline) {
            for (int off = 0; off < payload.length; off += chunk.length) {
                int len = Math.min(chunk.length, payload.length - off);
                System.arraycopy(payload, off, chunk, 0, len);
                emulator.append(chunk, len);
                bytes += len;
            }
            loops++;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("loops=" + loops + " bytes=" + bytes + " elapsedMs=" + elapsedMs
                + " MB/s=" + (bytes / 1024.0 / 1024.0 / (elapsedMs / 1000.0)));
    }
}