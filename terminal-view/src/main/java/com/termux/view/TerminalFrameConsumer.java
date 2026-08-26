package com.termux.view;

/**
 * Backend-neutral consumer of {@link TerminalRenderFrame}s.
 *
 * <p>Each backend (Canvas, retained layer, row bitmap, Surface, GLES) implements
 * this interface. The consumer is attached once per render target/generation,
 * receives immutable frames together with their {@link RenderDamage}, and
 * reports counters via {@link #snapshot()}.</p>
 */
public interface TerminalFrameConsumer {

    /**
     * Attach to a new render target/generation.
     *
     * @param renderGeneration generation identifying this target; frames from
     *                         older generations must be ignored by the consumer.
     * @param geometry         render target geometry in cells and pixels.
     */
    void attach(long renderGeneration, RenderGeometry geometry);

    /**
     * Submit a frame and its damage for rendering.
     *
     * @param frame            immutable frame to render.
     * @param damage           immutable damage relative to the previously submitted frame.
     * @param identity         frame identity used to correlate ack stages.
     * @param renderGeneration generation this consumer was attached with.
     */
    void submit(TerminalRenderFrame frame, RenderDamage damage,
                TerminalFrameIdentity identity, long renderGeneration);

    /**
     * Detach from the current render target. After this call the consumer must
     * not render any more frames until attached to a new generation.
     *
     * @param renderGeneration the generation this consumer was attached with.
     */
    void detach(long renderGeneration);

    /**
     * Detach and wait for any in-flight frames of this generation to complete.
     *
     * <p>Synchronous backends (Canvas) can return immediately. Asynchronous
     * backends must block until the previously submitted frame has been fully
     * processed or the timeout elapses.</p>
     *
     * @param renderGeneration the generation this consumer was attached with.
     * @param timeoutMs        maximum time to wait for in-flight work.
     * @return true if the consumer detached cleanly and no frames are in flight.
     */
    default boolean detachAndJoin(long renderGeneration, long timeoutMs) {
        detach(renderGeneration);
        return true;
    }

    /** Return a snapshot of rendering counters. */
    RenderStats snapshot();
}
