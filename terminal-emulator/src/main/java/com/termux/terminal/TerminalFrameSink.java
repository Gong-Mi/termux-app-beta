package com.termux.terminal;

/**
 * Callback used by {@link TerminalParserWorker} to hand off an immutable model
 * frame to the render side. The sink is normally implemented in the view layer
 * and converts the model frame into a {@code TerminalRenderFrame} (or pushes it
 * through a mailbox).
 */
public interface TerminalFrameSink {
    /**
     * Publish a new immutable model frame to the render side.
     *
     * <p>Called from the parser worker thread. Implementations should copy or
     * forward the frame quickly and return; heavy work belongs on the consumer
     * thread.</p>
     */
    void publishFrame(TerminalModelFrame frame);

    /**
     * Return {@code true} if the worker should capture a fresh snapshot for the
     * next frame. When this returns {@code false} the worker may skip the
     * snapshot/copy cost and rely on {@link #onFrameConsumed(TerminalModelFrame)}
     * to republish later.
     *
     * <p>The default implementation always returns {@code true}, preserving the
     * prior behaviour for sinks that do not participate in latest-only flow control.</p>
     */
    default boolean shouldCaptureSnapshot() {
        return true;
    }

    /**
     * Notify the producer that a previously published frame has been consumed by
     * the renderer. This gives the producer a chance to republish if it skipped
     * snapshots while a frame was pending.
     *
     * <p>The default implementation does nothing.</p>
     */
    default void onFrameConsumed(TerminalModelFrame frame) {
    }
}
