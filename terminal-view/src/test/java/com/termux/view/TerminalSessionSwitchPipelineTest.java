package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalScreenSnapshot;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Conformance (#51 matrix): "session A→B stale frame rejection" exercised END TO
 * END over the real production pipeline — emulator → parser-owned model frame →
 * UI projection (TerminalRenderFrame) → consumer mailbox → TerminalFramePump →
 * backend sink — with a session-generation switch in the middle.
 *
 * The invariant: after the A→B switch, neither queued nor freshly published
 * generation-A frames may reach the B sink. Every stage must refuse A independently:
 * the mailbox rejects on identity, the pump on its detached flag, and the
 * View-level sink closure checks its own session gate before publishing.
 */
public class TerminalSessionSwitchPipelineTest {

    private static final class TestSink implements TerminalFramePump.Sink<TerminalRenderFrame> {
        final List<TerminalFrameConsumerMailbox.Entry<TerminalRenderFrame>> received = new ArrayList<>();

        @Override public void accept(TerminalFrameConsumerMailbox.Entry<TerminalRenderFrame> entry) {
            received.add(entry);
        }
    }

    private static final class ImmediateExecutor implements Executor {
        @Override public void execute(Runnable command) { command.run(); }
    }

    private static TerminalEmulator emulator() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        return new TerminalEmulator(output, 8, 4, 13, 15, 8, null);
    }

    private static void feed(TerminalEmulator em, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        em.append(b, b.length);
    }

    /**
     * Mirror of TerminalParserWorker.publishFrame's capture step: journal count is
     * read first, bits cleared only when non-zero (the exact emit order production uses).
     */
    private static TerminalModelFrame captureModel(TerminalEmulator em,
                                                   TerminalScreenSnapshot previous) {
        int dirtyCount = em.getScreen().getDirtyMutationCount();
        long[] dirtyBits = dirtyCount == 0 ? null : em.getScreen().getAndClearDirtyRowBits();
        return new TerminalModelFrame(em, 0, dirtyBits, dirtyCount, previous);
    }

    /** Mirrors buildAndPublishProjectionFrame minus Android View dependencies. */
    private static TerminalRenderFrame project(TerminalModelFrame model) {
        return new TerminalRenderFrame(model, 0, 0, -1, -1);
    }

    @Test
    public void generationAFrameNeverReachesGenerationBPipeline() {
        // ---- Session A: live pipeline mailbox_A -> pump_A -> sink_A ----
        TerminalEmulator emA = emulator();
        feed(emA, "AAA");
        TestSink sinkA = new TestSink();
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mailboxA =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 7L, 3L);
        TerminalFramePump<TerminalRenderFrame> pumpA =
            new TerminalFramePump<>(mailboxA, sinkA, new ImmediateExecutor());

        TerminalModelFrame modelA = captureModel(emA, null);
        TerminalRenderFrame renderA = project(modelA);
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED, mailboxA.submit(
            renderA, new TerminalFrameIdentity(7L, 3L, modelA.getScreenRevision(), 1L)));
        pumpA.requestDelivery();
        assertEquals("generation A frame flows through while attached", 1, sinkA.received.size());
        assertTrue(sinkA.received.get(0).frame.screen.getTranscriptText().contains("AAA"));

        // ---- Switch: detach/join A pipeline (as TerminalView.attachSession does) ----
        assertTrue(pumpA.detachAndJoin(5_000L));
        assertTrue(sinkA.received.stream().allMatch(e -> e.identity.sessionGeneration == 7L));

        // ---- Session B: NEW emulator, NEW mailbox_B -> pump_B -> sink_B ----
        TerminalEmulator emB = emulator();
        feed(emB, "BBB");
        TestSink sinkB = new TestSink();
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mailboxB =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 8L, 4L);
        TerminalFramePump<TerminalRenderFrame> pumpB =
            new TerminalFramePump<>(mailboxB, sinkB, new ImmediateExecutor());

        // Stale arrival 1: an A-frame still queued in pipeline B must be refused by identity.
        TerminalModelFrame lateModelA = captureModel(emA, modelA.screen); // emA mutated further below
        feed(emA, "!");

        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.REJECTED_INCOMPATIBLE,
            mailboxB.submit(project(lateModelA),
                new TerminalFrameIdentity(7L, 3L, lateModelA.getScreenRevision(), 2L)));

        pumpB.requestDelivery();
        assertEquals("B sink must not receive any A-generation frame", 0, sinkB.received.size());

        // The rejected frame also never entered the mailbox slot.
        assertNull(mailboxB.peekLatest());

        // Stale arrival 2: even a detached pump_A cannot leak the queued A frame to anyone.
        feed(emA, "?");
        pumpA.requestDelivery(); // detached: schedules nothing
        assertEquals(0, sinkB.received.size());
        assertEquals("detached pump_A stopped at one delivery", 1, sinkA.received.size());

        // ---- Fresh B flow works end to end ----
        TerminalModelFrame modelB = captureModel(emB, null);
        TerminalRenderFrame renderB = project(modelB);
        assertEquals(TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED, mailboxB.submit(
            renderB, new TerminalFrameIdentity(8L, 4L, modelB.getScreenRevision(), 1L)));
        pumpB.requestDelivery();
        assertEquals(1, sinkB.received.size());
        assertEquals(8L, sinkB.received.get(0).identity.sessionGeneration);
        assertTrue(sinkB.received.get(0).frame.screen.getTranscriptText().contains("BBB"));
        assertNull(mailboxB.peekLatest()); // consumed by the pump

        assertNotNull(renderA.screen.rowAtExternal(0)); // A artifacts untouched
    }
}
