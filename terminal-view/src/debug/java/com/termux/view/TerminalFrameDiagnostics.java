package com.termux.view;

import android.util.Log;

import com.termux.terminal.TerminalParserMetrics;
import com.termux.terminal.TerminalSession;

/** Debug-only per-frame diagnostics. This source file is not compiled into release. */
final class TerminalFrameDiagnostics {

    private static volatile boolean sEnabled;

    private TerminalFrameDiagnostics() {}

    static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    static void logIfEnabled(TerminalSession session, RenderFrameMetrics metrics, TerminalRenderFrame frame) {
        if (!sEnabled) return;

        TerminalParserMetrics.Snapshot parser = session.getParserMetricsSnapshot();
        int dirtyInView = 0;
        for (int row = frame.topRow; row < frame.endRow; row++) {
            if (frame.rowNeedsRedraw(row)) dirtyInView++;
        }
        Log.i("Termux:TerminalView", "frame rev=" + frame.screenRevision
            + " published=" + metrics.getPublishedFrameCount() + " lastPublishedRev=" + metrics.getLastPublishedScreenRevision()
            + " drawn=" + metrics.getDrawnFrameCount() + " lastDrawnRev=" + metrics.getLastDrawnScreenRevision()
            + " dropped=" + metrics.getDroppedFrameCount() + " coalesced=" + metrics.getCoalescedRevisionCount() + " acked=" + metrics.getLastAckedScreenRevision()
            + " parserBytes=" + parser.inputBytes + " appendCommands=" + parser.appendCommands
            + " controlCommands=" + parser.controlCommands + " parserFrames=" + parser.publishedFrames
            + " finishCommands=" + parser.finishCommands + " stopCommands=" + parser.stopCommands
            + " mutations=" + frame.dirtyMutationCount
            + " visible=" + (frame.endRow - frame.topRow) + " redrawWorthies=" + dirtyInView
            + " cursor=" + (frame.cursorVisible ? frame.cursorRow : "hidden")
            + " sel=" + frame.selectionY1 + ".." + frame.selectionY2);
    }
}
