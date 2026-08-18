package com.termux.view;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A single-slot, latest-only mailbox between the terminal parser worker thread
 * and the main/UI render thread.
 *
 * <p>The parser produces immutable {@link TerminalRenderFrame} snapshots and
 * publishes them here. The renderer acquires the latest frame on the UI thread
 * and draws it. If the parser outruns the renderer, older frames are replaced
 * without being rendered: they are counted as dropped by the mailbox so that
 * the {@link RenderFrameMetrics} invariant {@code published >= drawn + dropped}
 * remains observable.</p>
 */
public final class TerminalRenderMailbox {

    private final RenderFrameMetrics mMetrics;
    private final AtomicReference<TerminalRenderFrame> mSlot = new AtomicReference<>();

    public TerminalRenderMailbox(RenderFrameMetrics metrics) {
        mMetrics = metrics;
    }

    /**
     * Called from the parser worker thread.
     *
     * <p>Places {@code frame} in the slot. If a previous frame was still
     * unrendered, it is dropped and the metrics drop counter is incremented.
     * The published counter is always incremented for the new frame.</p>
     */
    public void publish(TerminalRenderFrame frame) {
        if (frame == null) throw new IllegalArgumentException("frame is null");
        TerminalRenderFrame previous = mSlot.getAndSet(frame);
        if (previous != null) {
            mMetrics.drop();
        }
        mMetrics.publish(frame.screenRevision);
    }

    /**
     * Called from the render/UI thread.
     *
     * <p>Returns the latest frame produced by the parser worker, or {@code null}
     * if no frame has been published since the last acquisition. The caller
     * should render the frame and then call {@link RenderFrameMetrics#ack(long)}
     * with {@link TerminalRenderFrame#screenRevision}.</p>
     */
    public TerminalRenderFrame acquireLatest() {
        return mSlot.getAndSet(null);
    }

    /** Peek without consuming; intended for tests and diagnostics only. */
    TerminalRenderFrame peek() {
        return mSlot.get();
    }
}
