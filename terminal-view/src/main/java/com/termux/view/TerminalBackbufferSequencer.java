package com.termux.view;

import java.util.function.LongSupplier;

/**
 * Full-frame backbuffer sequencer for the #52 SurfaceView route (spike step 3:
 * "先只做 full-frame backbuffer"). Owns the render thread's per-cycle decision
 * core; pixel mechanics sit behind {@link Ops} so this protocol is JVM-verifiable
 * before an Android shell exists.
 *
 * <p>One {@link #step()} is one render-thread cycle:
 * acquire latest → validate against the LIVE surface epoch → resize backbuffer if
 * needed → rasterize EVERYTHING (no incremental reuse at this stage, per #52's
 * "不能把两个风险一起引入") → present once. The presented marker advances only on
 * confirmed presentation; a lost surface keeps it untouched and drops the frame
 * (the persistent backbuffer assumption makes every later full repaint
 * self-sufficient).</p>
 *
 * <p>Single-thread contract: step() runs on exactly one thread (the owner of
 * {@code Ops}); mailbox/epoch supplier provide the cross-thread visibility.</p>
 */
public final class TerminalBackbufferSequencer {

    /** Pixel/surface mechanics injected by the Android shell. */
    public interface Ops {
        /** Whether the pixel target is sized and safe for drawAll. */
        boolean pixelSizeReady();

        /** Backbuffer must match this geometry; recreate when it differs. */
        void resizeIfNeeded(int width, int height);

        /** Rasterize the FULL viewport of this frame into the backbuffer. */
        void drawAll(TerminalRenderFrame frame);

        /**
         * Submit the backbuffer to the live Surface (lockCanvas + blit + post).
         *
         * @return false when the surface vanished mid-present (epoch was right,
         *         but the surface refused); caller must not advance markers.
         */
        boolean present();
    }

    public enum StepResult {
        /** Nothing pending in the mailbox. */
        IDLE,
        /** Frame drawn and presented; marker advanced. */
        PRESENTED,
        /** Pending frame dropped because no live surface exists. */
        SKIPPED_NO_SURFACE,
        /** Epoch matched but the surface refused the present; marker unchanged. */
        SURFACE_LOST
    }

    private final TerminalFrameConsumerMailbox<TerminalRenderFrame> mMailbox;
    private final LongSupplier mLiveEpoch;
    private final Ops mOps;

    private long mPresentedSessionGeneration = -1L;
    private long mPresentedTargetGeneration = -1L;
    private long mPresentedModelRevision = -1L;
    private long mPresentedProjectionRevision = -1L;
    private long mLastSeenEpoch = -1L;
    private boolean mLastEpochWasGeometryChange;

    public TerminalBackbufferSequencer(TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox,
                                       LongSupplier liveEpoch, Ops ops) {
        mMailbox = mailbox;
        mLiveEpoch = liveEpoch;
        mOps = ops;
    }

    public StepResult step() {
        // Draw raced ahead of the first pixel resize: acquiring now would drop
        // the frame (acquireLatest() is take-not-peek) into a backbuffer that
        // cannot raster it. Leave the frame queued; onSurfaceChanged sizes the
        // bitmap and the next requestCycle() serves it.
        if (!mOps.pixelSizeReady()) {
            return StepResult.IDLE;
        }
        TerminalFrameConsumerMailbox.Entry<TerminalRenderFrame> entry = mMailbox.acquireLatest();
        if (entry == null) {
            return StepResult.IDLE;
        }
        long epoch = mLiveEpoch.getAsLong();
        if (epoch <= 0L) {
            // No live surface: the frame cannot be presented. Drop it — the next
            // accepted frame after surfaceCreated paints the full screen anyway.
            return StepResult.SKIPPED_NO_SURFACE;
        }

        TerminalRenderFrame frame = entry.frame;
        mOps.resizeIfNeeded(frame.columns, frame.endRow - frame.topRow);
        mOps.drawAll(frame);

        if (!mOps.present()) {
            // Surface died between validation and present; do NOT advance markers.
            return StepResult.SURFACE_LOST;
        }

        mLastEpochWasGeometryChange = (epoch != mLastSeenEpoch);
        mLastSeenEpoch = epoch;

        TerminalFrameIdentity id = entry.identity;
        mPresentedSessionGeneration = id.sessionGeneration;
        mPresentedTargetGeneration = id.targetGeneration;
        mPresentedModelRevision = id.modelRevision;
        mPresentedProjectionRevision = id.projectionRevision;
        return StepResult.PRESENTED;
    }

    /** Session generation of the last CONFIRMED presentation; -1 before any. */
    public long lastPresentedSessionGeneration() {
        return mPresentedSessionGeneration;
    }

    public long lastPresentedTargetGeneration() {
        return mPresentedTargetGeneration;
    }

    public long lastPresentedModelRevision() {
        return mPresentedModelRevision;
    }

    public long lastPresentedProjectionRevision() {
        return mPresentedProjectionRevision;
    }

    /** Whether the most recent presentation followed a surface epoch change. */
    public boolean lastEpochWasGeometryChange() {
        return mLastEpochWasGeometryChange;
    }
}
