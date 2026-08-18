package com.termux.terminal;

/**
 * Callback used by {@link TerminalParserWorker} to hand off an immutable model
 * frame to the render side. The sink is normally implemented in the view layer
 * and converts the model frame into a {@code TerminalRenderFrame} (or pushes it
 * through a mailbox).
 */
public interface TerminalFrameSink {
    void publishFrame(TerminalModelFrame frame);
}
