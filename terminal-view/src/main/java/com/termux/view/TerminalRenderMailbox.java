package com.termux.view;

import com.termux.terminal.FrameRevision;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A single-slot, latest-only mailbox between a producer thread (the terminal
 * parser worker) and a consumer thread (the main/UI render thread).
 *
 * <p>The producer calls {@link #publish(T)} whenever a new frame is ready. The
 * consumer calls {@link #acquireLatest()} from the render thread to get the
 * latest frame, dropping any intermediate frames.</p>
 *
 * @param <T> The concrete frame type; must expose a screen revision for metrics.
 */
public final class TerminalRenderMailbox<T extends FrameRevision> {

    private final RenderFrameMetrics mMetrics;
    private final AtomicReference<T> mSlot = new AtomicReference<>();

    public TerminalRenderMailbox(RenderFrameMetrics metrics) {
        mMetrics = metrics;
    }

    /**
     * Called from the producer thread.
     *
     * <p>Places {@code frame} in the slot. If a previous frame was still
     * unrendered, it is dropped and the metrics drop counter is incremented.
     * The published counter is always incremented for the new frame.</p>
     */
    public void publish(T frame) {
        if (frame == null) throw new IllegalArgumentException("frame is null");
        T previous = mSlot.getAndSet(frame);
        if (previous != null) {
            mMetrics.drop();
        }
        mMetrics.publish(frame.getScreenRevision());
    }

    /**
     * Called from the render/UI thread.
     *
     * <p>Returns the latest frame produced by the producer, or {@code null}
     * if no frame has been published since the last acquisition. The caller
     * should render the frame and then call {@link RenderFrameMetrics#ack(long)}
     * with the frame's revision.</p>
     */
    public T acquireLatest() {
        return mSlot.getAndSet(null);
    }

    /** Peek without consuming; intended for tests and diagnostics only. */
    T peek() {
        return mSlot.get();
    }
}
