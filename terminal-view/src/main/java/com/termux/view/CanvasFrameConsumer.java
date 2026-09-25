package com.termux.view;

import android.graphics.Canvas;
import android.view.View;

/**
 * Canvas backend implementation of {@link TerminalFrameConsumer}.
 *
 * <p>This consumer wraps {@link TerminalRenderer} and is designed to be called
 * synchronously from {@link TerminalView#onDraw(android.graphics.Canvas)}. The
 * caller must set the current canvas with {@link #setCanvas(Canvas)} before each
 * {@link #submit(TerminalRenderFrame, RenderDamage)}.</p>
 */
public final class CanvasFrameConsumer implements TerminalFrameConsumer {

    private final TerminalRenderer mRenderer;
    private final RenderFrameMetrics mMetrics;
    private final View mView;

    private long mGeneration;
    private boolean mAttached;
    private Canvas mCanvas;
    private TerminalRenderFrame mLastSubmittedFrame;
    private TerminalFrameIdentity mLastSubmittedIdentity;

    private long mRasteredCount;
    private long mSubmittedCount;

    public CanvasFrameConsumer(TerminalRenderer renderer, RenderFrameMetrics metrics, View view) {
        mRenderer = renderer;
        mMetrics = metrics;
        mView = view;
    }

    /** Set the canvas for the next submit. Called from onDraw. */
    public void setCanvas(Canvas canvas) {
        mCanvas = canvas;
    }

    @Override
    public void attach(long renderGeneration, RenderGeometry geometry) {
        mGeneration = renderGeneration;
        mAttached = true;
        mLastSubmittedFrame = null;
        mLastSubmittedIdentity = null;
        mRasteredCount = 0;
        mSubmittedCount = 0;
    }

    @Override
    public void submit(TerminalRenderFrame frame, RenderDamage damage,
                       TerminalFrameIdentity identity, long renderGeneration) {
        if (!mAttached) {
            throw new IllegalStateException("CanvasFrameConsumer not attached");
        }
        if (mCanvas == null) {
            throw new IllegalStateException("Canvas not set");
        }
        if (renderGeneration != mGeneration) {
            // Frame belongs to a detached generation; ignore.
            return;
        }

        // Pixel retention is a property of the WINDOW + draw target, not of the
        // onDraw canvas flag alone: a LAYER_TYPE_SOFTWARE view inside a hardware
        // window draws on a software canvas, but that layer bitmap is redrawn
        // in its entirety per invalidation — conflating the two re-opens the
        // lost-row bug (disproven on device 2026-09-25 under hwui_gpu,
        // skipped=80/81). Only a software-rendered window drawing directly into
        // its persistent surface retains prior pixels.
        boolean skipCleanRows = CanvasRetentionPolicy.shouldSkipCleanRows(
            mView.isHardwareAccelerated(),
            mView.getLayerType(),
            damage.fullRedraw,
            mLastSubmittedFrame != null,
            frame.reverseVideo);

        mRenderer.render(frame, mCanvas, skipCleanRows, mLastSubmittedFrame);
        // The reference Canvas render has completed successfully. For a hardware
        // Canvas this is still command generation, not physical presentation.
        mRasteredCount++;
        mSubmittedCount++;
        mLastSubmittedFrame = frame;
        mLastSubmittedIdentity = identity;
        mMetrics.ack(frame.screenRevision);
    }

    @Override
    public void detach(long renderGeneration) {
        if (renderGeneration != mGeneration) return;
        mAttached = false;
        mCanvas = null;
        mLastSubmittedFrame = null;
        mLastSubmittedIdentity = null;
    }

    @Override
    public RenderStats snapshot() {
        // Canvas backend has no evidence of presented frames.
        // Keep legacy published/drawn/dropped counters from RenderFrameMetrics for
        // compatibility with existing diagnostics/smoke verifiers while also exposing
        // the new stage counters.
        return new RenderStats(
            mMetrics.getPublishedFrameCount(),
            mMetrics.getDrawnFrameCount(),
            mMetrics.getDroppedFrameCount(),
            mRasteredCount,
            mSubmittedCount,
            0L,
            0L, 0L, 0L, 0L,
            mMetrics.getLastPublishedScreenRevision(),
            mMetrics.getLastDrawnScreenRevision(),
            mMetrics.getCoalescedRevisionCount());
    }

    public long getGeneration() {
        return mGeneration;
    }

    public boolean isAttached() {
        return mAttached;
    }

    public TerminalRenderFrame getLastSubmittedFrame() {
        return mLastSubmittedFrame;
    }

    public TerminalFrameIdentity getLastSubmittedIdentity() {
        return mLastSubmittedIdentity;
    }
}
