package com.termux.view;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalScreenSnapshot;

import java.nio.charset.StandardCharsets;

/**
 * Backend-neutral conformance scenario library for {@link TerminalFrameConsumer}
 * (issue #57 P2 / #51 conformance matrix, JVM layers).
 *
 * <p>Every backend — Canvas, retained layer, row bitmap, Surface backbuffer,
 * GLES, or any test fake — must satisfy the SAME consumer contract regardless
 * of its threading model. This class drives a backend factory through the
 * matrix cells exercisable at JVM level and asserts the shared postconditions.
 * The three-layer organization demanded by #57:</p>
 *
 * <ol>
 *   <li><b>Data protocol</b> (this layer): frames, damage and identities cross
 *       the boundary intact; latest-only replacement keeps the next damage
 *       computed against the last SUBMITTED frame, not the dropped one.</li>
 *   <li><b>Consumer lifecycle</b> (this layer): attach binds generation+geometry
 *       and resets backend bookkeeping; foreign/detached generations never reach
 *       the backend; post-join silence holds for every threading model.</li>
 *   <li><b>Canvas reference pixel</b> (NOT this layer): verified by the emulator
 *       render smoke against real Canvas output. JVM conformance makes no
 *       View/Looper/present claims (issue #57 evidence boundary).</li>
 * </ol>
 *
 * <p>Observable delivery is exposed by the factory via {@link BackendFactory#deliveredFrames()}
 * so scenarios have real teeth on every backend: the async fake counts backend
 * callbacks, the synchronous mirror counts rasters, the Canvas backend (under
 * instrumentation) counts its raster counter.</p>
 */
public final class TerminalFrameConsumerConformance {

    /** Creates the consumer under test and describes its threading shape. */
    public interface BackendFactory {
        /**
         * A fresh consumer in the DETACHED state. The scenario attaches it with
         * {@link TerminalFrameConsumer#attach(long, RenderGeometry)}; re-attach
         * to a new generation goes through the SAME instance (the target-rebind
         * pattern), so backends must support multiple attachments.
         */
        TerminalFrameConsumer create();

        /**
         * Whether submit() completes the backend work before returning
         * (synchronous/pull style) or queues it (push style; harness drains).
         */
        boolean isSynchronous();

        /** For push-style backends: run queued backend work to completion. */
        void drain();

        /** Frames that actually reached the backend so far (may lag until drain). */
        long deliveredFrames();

        /** Release backend resources after a scenario (executor shutdown etc.). */
        void close();
    }

    /** Assertion helper so the runner can be driven from any test framework. */
    public interface Check {
        void that(boolean condition, String message);
    }

    private final BackendFactory mFactory;
    private final Check mCheck;

    public TerminalFrameConsumerConformance(BackendFactory factory, Check check) {
        mFactory = factory;
        mCheck = check;
    }

    private void that(boolean condition, String message) {
        mCheck.that(condition, message);
    }

    // ---------------------------------------------------------------------
    // Shared frame helpers: a real emulator drives real model frames so the
    // data-protocol assertions exercise the actual snapshot/damage machinery.
    // ---------------------------------------------------------------------

    private static final class NoOpOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }

    /** Capture one model frame at the bottom viewport with no prior provenance. */
    public static TerminalRenderFrame frame(long revisionSeed) {
        return frame(revisionSeed, null);
    }

    /**
     * Capture one model frame, chaining row-reuse provenance from {@code previous}.
     *
     * <p>Feeds a single short line per capture so that row 0 changes every frame
     * while the remaining rows stay untouched (and provably shared through the
     * snapshot row-reuse machinery) — the same shape as the #51 drop-equivalence
     * cell. Input must stay under one line (8 columns) to keep later rows clean.</p>
     */
    public static TerminalRenderFrame frame(long revisionSeed, TerminalRenderFrame previous) {
        TerminalEmulator emulator = new TerminalEmulator(new NoOpOutput(), 8, 4, 13, 15, 8, null);
        String text = String.valueOf((char) ('A' + (int) (revisionSeed % 26)));
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        TerminalScreenSnapshot previousSnapshot = previous == null ? null : previous.screen;
        TerminalModelFrame model = new TerminalModelFrame(emulator, 0,
            emulator.getScreen().getAndClearDirtyRowBits(),
            emulator.getScreen().getDirtyMutationCount(),
            previousSnapshot);
        return new TerminalRenderFrame(model, 0, -1, -1, -1, -1);
    }

    /**
     * A shared emulator bound to one scenario, so successive captures chain the
     * REAL row-reuse provenance (same buffer identity) instead of independent
     * emulators whose rows can never be shared.
     */
    public static final class EmulatorFeed {
        private final TerminalEmulator mEmulator;

        public EmulatorFeed() {
            mEmulator = new TerminalEmulator(new NoOpOutput(), 8, 4, 13, 15, 8, null);
        }

        /** Feed one short line and capture a frame chaining {@code previous}. */
        public TerminalRenderFrame feed(String line, TerminalRenderFrame previous) {
            byte[] input = line.getBytes(StandardCharsets.UTF_8);
            mEmulator.append(input, input.length);
            TerminalScreenSnapshot previousSnapshot = previous == null ? null : previous.screen;
            TerminalModelFrame model = new TerminalModelFrame(mEmulator, 0,
                mEmulator.getScreen().getAndClearDirtyRowBits(),
                mEmulator.getScreen().getDirtyMutationCount(),
                previousSnapshot);
            return new TerminalRenderFrame(model, 0, -1, -1, -1, -1);
        }
    }

    private static RenderGeometry geometry() {
        return new RenderGeometry(8, 4, 100, 100);
    }

    private static TerminalFrameIdentity identity(long generation, TerminalRenderFrame frame,
                                                   long projectionRevision) {
        return new TerminalFrameIdentity(generation, generation,
            frame.getScreenRevision(), projectionRevision);
    }

    // ---------------------------------------------------------------------
    // Layer 1 — data protocol scenarios
    // ---------------------------------------------------------------------

    /**
     * Cell: the first submit after attach is a full redraw (damage computed
     * against a null previous), and the frame reaches the backend intact.
     */
    public void dataProtocol_firstFrameFullRedraw() {
        TerminalFrameConsumer consumer = mFactory.create();
        consumer.attach(11L, geometry());
        try {
            TerminalRenderFrame f = frame(1);
            RenderDamage damage = RenderDamage.compute(f, null);
            that(damage.fullRedraw, "first frame after attach must be a full redraw");
            long before = mFactory.deliveredFrames();
            consumer.submit(f, damage, identity(11L, f, 1L), 11L);
            mFactory.drain();
            that(mFactory.deliveredFrames() == before + 1,
                "first frame must reach the backend exactly once (delivered="
                    + mFactory.deliveredFrames() + ", before=" + before + ")");
        } finally {
            mFactory.close();
        }
    }

    /**
     * Cell (drop equivalence, consumer side): after latest-only replacement the
     * damage handed to the NEXT submit must describe reality against the last
     * SUBMITTED frame, not the dropped one — changed rows not skipped, no
     * fabricated full-redraw, untouched rows provably shared.
     */
    public void dataProtocol_damageAfterDroppedIntermediate() {
        TerminalFrameConsumer consumer = mFactory.create();
        consumer.attach(12L, geometry());
        try {
            EmulatorFeed feed = new EmulatorFeed();
            TerminalRenderFrame f0 = feed.feed("AB", null);       // drawn baseline
            TerminalRenderFrame f1 = feed.feed("C", f0);          // published, replaced in flight
            TerminalRenderFrame f2 = feed.feed("D", f1);          // replacement; provenance chains f0→f1→f2

            consumer.submit(f0, RenderDamage.compute(f0, null), identity(12L, f0, 1L), 12L);
            mFactory.drain();

            // f1 is published-but-never-submitted (mailbox replaced it); the next
            // submit computes damage against f0, the last SUBMITTED frame.
            RenderDamage damage = RenderDamage.compute(f2, f0);
            that(!damage.fullRedraw,
                "coalesced content change must not demand a full redraw");
            that(!damage.rowUnchanged(f2, f0, 0),
                "changed row must be marked dirty against the last submitted frame");
            that(damage.rowUnchanged(f2, f0, 3),
                "untouched row must stay provably shared across the dropped intermediate");

            long before = mFactory.deliveredFrames();
            consumer.submit(f2, damage, identity(12L, f2, 3L), 12L);
            mFactory.drain();
            that(mFactory.deliveredFrames() == before + 1,
                "replacement frame must reach the backend exactly once");
        } finally {
            mFactory.close();
        }
    }

    // ---------------------------------------------------------------------
    // Layer 2 — consumer lifecycle scenarios
    // ---------------------------------------------------------------------

    /** Cell: frames from a foreign generation never touch the backend. */
    public void lifecycle_foreignGenerationIgnored() {
        TerminalFrameConsumer consumer = mFactory.create();
        consumer.attach(13L, geometry());
        try {
            TerminalRenderFrame f = frame(20);
            consumer.submit(f, RenderDamage.compute(f, null), identity(13L, f, 1L), 42L);
            mFactory.drain();
            that(mFactory.deliveredFrames() == 0,
                "foreign-generation submit must not reach the backend (delivered="
                    + mFactory.deliveredFrames() + ")");
        } finally {
            mFactory.close();
        }
    }

    /**
     * Cell: submit after detach is refused (IllegalStateException on both
     * reference implementations) — a detached target accepts no work.
     */
    public void lifecycle_submitAfterDetachRefused() {
        TerminalFrameConsumer consumer = mFactory.create();
        consumer.attach(14L, geometry());
        consumer.detach(14L);
        TerminalRenderFrame stale = frame(30);
        boolean refused = false;
        try {
            consumer.submit(stale, RenderDamage.compute(stale, null), identity(14L, stale, 1L), 14L);
        } catch (IllegalStateException expected) {
            refused = true;
        }
        that(refused, "submit after detach must be refused with IllegalStateException");
        mFactory.drain();
        that(mFactory.deliveredFrames() == 0, "refused submit must not reach the backend");
        mFactory.close();
    }

    /**
     * Cell: after detach, re-attach to a NEW generation accepts work again,
     * while submits carrying the OLD generation stay ignored — the target-rebind
     * pattern TerminalView performs on pixel resize.
     */
    public void lifecycle_detachThenReattachNewGeneration() {
        TerminalFrameConsumer consumer = mFactory.create();
        consumer.attach(15L, geometry());
        try {
            TerminalRenderFrame f1 = frame(31);
            consumer.submit(f1, RenderDamage.compute(f1, null), identity(15L, f1, 1L), 15L);
            mFactory.drain();
            long deliveredOld = mFactory.deliveredFrames();
            that(deliveredOld == 1, "pre-detach frame must have been delivered");

            consumer.detach(15L);
            consumer.attach(16L, geometry());

            // Stale-generation submit after re-attach: silently ignored.
            TerminalRenderFrame stale = frame(32);
            consumer.submit(stale, RenderDamage.compute(stale, null), identity(15L, stale, 2L), 15L);
            mFactory.drain();
            that(mFactory.deliveredFrames() == deliveredOld,
                "stale-generation submit after re-attach must not deliver");

            // New-generation submit: accepted and delivered.
            TerminalRenderFrame f2 = frame(33);
            consumer.submit(f2, RenderDamage.compute(f2, null), identity(16L, f2, 1L), 16L);
            mFactory.drain();
            that(mFactory.deliveredFrames() == deliveredOld + 1,
                "new-generation submit after re-attach must deliver");
        } finally {
            mFactory.close();
        }
    }

    /**
     * Cell (join, synchronous and asynchronous): after a successful join the
     * backend receives nothing more — including stale-generation submits issued
     * after the consumer is re-attached to a new generation (producer keeps
     * producing). True means NO frame is still being processed.
     */
    public void lifecycle_postJoinSilence() {
        TerminalFrameConsumer consumer = mFactory.create();
        consumer.attach(16L, geometry());
        try {
            TerminalRenderFrame f = frame(40);
            consumer.submit(f, RenderDamage.compute(f, null), identity(16L, f, 1L), 16L);
            mFactory.drain();
            boolean joined = consumer.detachAndJoin(16L, 10_000L);
            that(joined, "join must succeed within the timeout");
            long deliveredAtJoin = mFactory.deliveredFrames();

            // Producer keeps producing; the drained consumer is re-attached to a
            // new generation but the late frame still carries the old one.
            consumer.attach(17L, geometry());
            TerminalRenderFrame late = frame(41);
            consumer.submit(late, RenderDamage.compute(late, null), identity(16L, late, 2L), 16L);
            mFactory.drain();
            that(mFactory.deliveredFrames() == deliveredAtJoin,
                "post-join stale-generation submit must not deliver (delivered="
                    + mFactory.deliveredFrames() + ", at-join=" + deliveredAtJoin + ")");
        } finally {
            mFactory.close();
        }
    }
}
