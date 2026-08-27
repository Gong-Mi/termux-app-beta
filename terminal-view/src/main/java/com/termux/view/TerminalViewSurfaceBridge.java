package com.termux.view;

import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * View-scoped host for the #52 spike surface route (step 3b): owns a dedicated
 * latest-only mailbox + {@link TerminalSurfaceRenderThread} + real
 * {@link TerminalSurfaceBackbuffer}, and forwards SurfaceHolder callbacks into
 * the render thread's blocked-destroy lifecycle.
 *
 * <p>The bridge is intentionally dumb: it never reads terminal state. The View
 * stays the producer (fan-out submit) and remains fully functional when the
 * bridge is absent (system/software/hwui_gpu modes).</p>
 *
 * <p>Threading: created/attached on the UI thread; {@link #publish} is called
 * from {@code buildAndPublishProjectionFrame} on the UI thread; the render
 * thread owns all pixel work. Every method is safe to call after
 * {@link #detachAndJoin} (no-ops).</p>
 */
final class TerminalViewSurfaceBridge implements SurfaceHolder.Callback {

    private final TerminalSurfaceRenderThread mRenderThread;
    private final TerminalFrameConsumerMailbox<TerminalRenderFrame> mMailbox;
    private final TerminalSurfaceBackbuffer mBackbuffer;
    private final RenderFrameMetrics mMetrics;
    private final SurfaceView mSurfaceView;
    private final AtomicBoolean mAttached = new AtomicBoolean(true);

    private TerminalViewSurfaceBridge(SurfaceView surfaceView,
                                      TerminalSurfaceRenderThread renderThread,
                                      TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox,
                                      TerminalSurfaceBackbuffer backbuffer,
                                      RenderFrameMetrics metrics) {
        mSurfaceView = surfaceView;
        mRenderThread = renderThread;
        mMailbox = mailbox;
        mBackbuffer = backbuffer;
        mMetrics = metrics;
    }

    /**
     * Create and start the bridge. Idempotent per call site: returns null if
     * the SurfaceView already has no holder surface (callbacks will follow).
     */
    @Nullable
    static TerminalViewSurfaceBridge create(SurfaceView surfaceView,
                                            TerminalRenderer renderer,
                                            RenderFrameMetrics metrics,
                                            long sessionGeneration,
                                            long targetGeneration,
                                            TerminalSurfaceBackbuffer.SessionSupplier sessionSupplier) {
        TerminalSurfaceBackbuffer backbuffer =
            new TerminalSurfaceBackbuffer(renderer, metrics, sessionSupplier);
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(metrics, sessionGeneration, targetGeneration);
        TerminalSurfaceRenderThread thread =
            new TerminalSurfaceRenderThread("view", mailbox, backbuffer);
        SurfaceHolder holder = surfaceView.getHolder();
        TerminalViewSurfaceBridge bridge = new TerminalViewSurfaceBridge(
            surfaceView, thread, mailbox, backbuffer, metrics);
        holder.addCallback(bridge);
        // Surface may already exist (view re-created while activity kept it):
        // the addCallback above does NOT replay creation, so install eagerly.
        Surface surface = holder.getSurface();
        if (surface != null && surface.isValid()) {
            backbuffer.setSurface(surface);
            thread.onSurfaceCreated();
            // Geometry is unknown here; surfaceChanged will follow from the
            // system and resize the backbuffer before any draw.
        }
        thread.start();
        return bridge;
    }

    /** Producer-side fan-out target. UI thread only. */
    void publish(TerminalRenderFrame frame, TerminalFrameIdentity identity) {
        if (!mAttached.get()) return;
        if (mMailbox.submit(frame, identity) == TerminalFrameConsumerMailbox.SubmitResult.ACCEPTED) {
            mRenderThread.requestCycle();
        }
    }

    /** Rebind to a new session generation without killing the render loop. */
    void rebind(long sessionGeneration, long targetGeneration) {
        if (!mAttached.get()) return;
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(mMetrics, sessionGeneration, targetGeneration);
        mRenderThread.rebind(mailbox);
    }

    /** Producer status for diagnostics. */
    boolean hasPendingFrame() {
        return mMailbox.peekLatest() != null;
    }

    /** Detach from the SurfaceHolder and stop the render thread. UI thread only. */
    void detachAndJoin() {
        if (!mAttached.compareAndSet(true, false)) return;
        mSurfaceView.getHolder().removeCallback(this);
        try {
            mRenderThread.shutdownAndJoin(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        mBackbuffer.setSurface(null);
    }

    /** Whether a presenting Surface is currently installed (epoch liveness probe). */
    boolean isSurfaceLive() {
        return mBackbuffer.hasSurface();
    }

    // ---- SurfaceHolder.Callback ----

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (!mAttached.get()) return;
        mBackbuffer.setSurface(holder.getSurface());
        mRenderThread.onSurfaceCreated();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!mAttached.get()) return;
        mRenderThread.onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (!mAttached.get()) return;
        // Blocks until the render loop can no longer touch the surface — the
        // Android SurfaceView contract — then closes the epoch.
        mRenderThread.onSurfaceBlockedDestroy();
        mBackbuffer.setSurface(null);
    }
}
