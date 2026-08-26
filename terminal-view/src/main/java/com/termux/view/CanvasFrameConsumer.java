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
            // Belongs to a detached generation; ignore.
            return;
        }

        boolean layered = mView.getLayerType() == View.LAYER_TYPE_HARDWARE
            || mView.getLayerType() == View.LAYER_TYPE_SOFTWARE;
        boolean skipCleanRows = !damage.fullRedraw && layered;

        mRenderer.render(frame, mCanvas, skipCleanRows, mLastSubmittedFrame);
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
        return new RenderStats(
            mMetrics.getPublishedFrameCount(),
            mMetrics.getDrawnFrameCount(),
            mMetrics.getDroppedFrameCount(),
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
