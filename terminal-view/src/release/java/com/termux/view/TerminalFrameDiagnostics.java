package com.termux.view;

import com.termux.terminal.TerminalSession;

/** Release stub: debug frame diagnostics are excluded from the release source set. */
final class TerminalFrameDiagnostics {

    private TerminalFrameDiagnostics() {}

    static void setEnabled(boolean enabled) {
        // Intentionally empty in release builds.
    }

    static void logIfEnabled(TerminalSession session, RenderFrameMetrics metrics, TerminalRenderFrame frame, int skippedRows) {
        // Intentionally empty in release builds.
    }
}
