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
     * @param frame  immutable frame to render.
     * @param damage immutable damage relative to the previously submitted frame.
     */
    void submit(TerminalRenderFrame frame, RenderDamage damage);

    /**
     * Detach from the current render target. After this call the consumer must
     * not render any more frames until attached to a new generation.
     *
     * @param renderGeneration the generation this consumer was attached with.
     */
    void detach(long renderGeneration);

    /** Return a snapshot of rendering counters. */
    RenderStats snapshot();
}
