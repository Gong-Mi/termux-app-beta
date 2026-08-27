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

    static void logIfEnabled(TerminalSession session, RenderFrameMetrics metrics, TerminalRenderFrame frame,
                             TerminalRenderStepMetrics.Snapshot renderSteps) {
        logIfEnabled("Termux:TerminalView", session, metrics, frame, renderSteps);
    }

    /** Variant with an explicit log tag so alternative pixel paths can identify their lines. */
    static void logIfEnabled(String logTag, TerminalSession session, RenderFrameMetrics metrics,
                             TerminalRenderFrame frame, TerminalRenderStepMetrics.Snapshot renderSteps) {
        if (!sEnabled) return;

        TerminalParserMetrics.Snapshot parser = session.getParserMetricsSnapshot();
        RenderFrameMetrics.Snapshot render = metrics.snapshot();
        int dirtyInView = 0;
        for (int row = frame.topRow; row < frame.endRow; row++) {
            if (frame.rowNeedsRedraw(row)) dirtyInView++;
        }
        Log.i(logTag, "frame rev=" + frame.screenRevision
            + " published=" + render.publishedFrameCount + " lastPublishedRev=" + render.lastPublishedScreenRevision
            + " drawn=" + render.drawnFrameCount + " lastDrawnRev=" + render.lastDrawnScreenRevision
            + " dropped=" + render.droppedFrameCount + " coalesced=" + render.coalescedRevisionCount + " acked=" + render.lastAckedScreenRevision
            + " parserBytes=" + parser.inputBytes + " appendCommands=" + parser.appendCommands
            + " controlCommands=" + parser.controlCommands + " parserFrames=" + parser.publishedFrames
            + " finishCommands=" + parser.finishCommands + " stopCommands=" + parser.stopCommands
            + " readNanos=" + parser.readNanos + " appendNanos=" + parser.appendNanos
            + " snapshotNanos=" + parser.snapshotNanos + " publishNanos=" + parser.publishNanos
            + " utf8Steps=" + parser.stepUtf8ContinuationBytes + " escapeSteps=" + parser.stepEscapeBytes
            + " csiSteps=" + parser.stepCsiBytes + " oscDcsSteps=" + parser.stepOscOrDcsBytes
            + " controlSteps=" + parser.stepControlBytes + " codePointCalls=" + parser.stepCodePointCalls
            + " plainEmitted=" + parser.stepPlainEmitted + " setCharCalls=" + parser.stepSetCharCalls
            + " scrollOps=" + parser.stepScrollOperations + " sgrSequences=" + parser.stepSgrSequences
            + " mutations=" + frame.dirtyMutationCount
            + " renderRows=" + renderSteps.visitedRows + " renderCells=" + renderSteps.visitedCells
            + " wcwidthCalls=" + renderSteps.wcWidthCalls + " glyphMeasureCalls=" + renderSteps.glyphMeasureCalls
            + " drawTextRunCalls=" + renderSteps.drawTextRunCalls + " drawRectCalls=" + renderSteps.drawRectCalls
            + " paintSetupNs=" + renderSteps.paintSetupNanos + " drawRectNs=" + renderSteps.drawRectNanos
            + " drawTextNs=" + renderSteps.drawTextNanos
            + " visible=" + (frame.endRow - frame.topRow) + " redrawWorthies=" + dirtyInView + " skipped=" + renderSteps.skippedRows
            + " cursor=" + (frame.cursorVisible ? frame.cursorRow : "hidden")
            + " sel=" + frame.selectionY1 + ".." + frame.selectionY2);
    }
}
