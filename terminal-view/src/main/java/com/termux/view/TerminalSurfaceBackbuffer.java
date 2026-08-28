package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

/**
 * Real Android backbuffer for the #52 spike (step 3b): a persistent software
 * {@link Bitmap} rasterized with the reference {@link TerminalRenderer} on the
 * render thread, presented to a SurfaceView Surface with a single
 * {@code lockCanvas + drawBitmap + unlockCanvasAndPost} blit.
 *
 * <p>Spike discipline (per #52): FULL-frame raster every cycle — no incremental
 * reuse. The bitmap persists between presents but every draw repaints it, so a
 * frame never depends on stale pixels.</p>
 *
 * <p>Threading: {@link #resizeTo}, {@link #drawAll} and {@link #present} run on
 * exactly one thread — the {@link TerminalSurfaceRenderThread} loop. The Surface
 * is installed/cleared from the app thread only outside any live epoch (the
 * blocked-destroy contract of the render thread guarantees no pixel traffic
 * after {@code onSurfaceBlockedDestroy()} returns, and before surfaceCreated
 * the epoch is 0 so no cycle can draw).</p>
 */
final class TerminalSurfaceBackbuffer implements TerminalSurfaceRenderThread.Backbuffer {

    private static final String LOG_TAG = "Termux:SurfaceBackbuffer";

    /** Session provider for frame diagnostics. */
    interface SessionSupplier {
        @Nullable TerminalSession get();
    }

    private final TerminalRenderer mRenderer;
    private final RenderFrameMetrics mMetrics;
    private final SessionSupplier mSessionSupplier;

    private volatile Surface mSurface;

    /** Render-thread-owned pixel state; never touched from other threads. */
    private Bitmap mBitmap;
    private Canvas mBitmapCanvas;
    private int mBitmapWidth;
    private int mBitmapHeight;

    /** Frame being drawn/presented in the current cycle (render-thread-owned). */
    private TerminalRenderFrame mCurrentFrame;
    private TerminalRenderStepMetrics.Snapshot mRenderStepsSnapshot;
    /**
     * Last frame whose pixels are provably complete in the bitmap. Only advances
     * after a confirmed present; the persistent bitmap retains those pixels, so
     * clean rows can be skipped exactly like the HWUI layered path (with a
     * strictly stronger retention guarantee — the bitmap is never reclaimed).
     */
    private TerminalRenderFrame mLastPresentedFrame;

    TerminalSurfaceBackbuffer(TerminalRenderer renderer, RenderFrameMetrics metrics,
                              SessionSupplier sessionSupplier) {
        mRenderer = renderer;
        mMetrics = metrics;
        mSessionSupplier = sessionSupplier;
    }

    /** Install or clear the presenting Surface. Called outside any live epoch. */
    void setSurface(@Nullable Surface surface) {
        mSurface = surface;
    }

    /** Whether a presenting Surface is currently installed. */
    boolean hasSurface() {
        return mSurface != null;
    }

    @Override
    public void resizeTo(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return;
        if (mBitmap != null && mBitmapWidth == widthPx && mBitmapHeight == heightPx) return;
        Bitmap replacement = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(replacement);
        canvas.drawColor(Color.BLACK);
        mBitmap = replacement;
        mBitmapCanvas = canvas;
        mBitmapWidth = widthPx;
        mBitmapHeight = heightPx;
        // New bitmap: no provably-presented pixels survive the swap; force the
        // next drawAll to full-frame (damage computation would otherwise trust
        // row content from a differently-sized bitmap).
        mLastPresentedFrame = null;
        Log.i(LOG_TAG, "backbuffer resized " + widthPx + "x" + heightPx);
    }

    @Override
    public void drawAll(TerminalRenderFrame frame) {
        if (mBitmapCanvas == null) return;
        Trace.beginSection("Termux:SurfaceBackbuffer.drawAll");
        try {
            // Damage from the last CONFIRMED-presented frame (not the last drawn):
            // the bitmap only provably holds pixels of frames whose present
            // succeeded. fullRedraw (geometry/palette/reverseVideo) drops the
            // reuse, matching the HWUI layered path's invariant.
            RenderDamage damage = RenderDamage.compute(frame, mLastPresentedFrame);
            boolean skipCleanRows = !damage.fullRedraw;
            if (!skipCleanRows) {
                mBitmapCanvas.drawColor(Color.BLACK);
            }
            mRenderer.render(frame, mBitmapCanvas, skipCleanRows, mLastPresentedFrame);
            mRenderStepsSnapshot = mRenderer.getAndResetRenderStepDelta();
            mCurrentFrame = frame;
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public boolean present() {
        TerminalRenderFrame frame = mCurrentFrame;
        if (mBitmap == null || frame == null) return false;
        Surface surface = mSurface;
        if (surface == null || !surface.isValid()) return false;

        Trace.beginSection("Termux:SurfaceBackbuffer.present");
        Canvas target = null;
        try {
            target = surface.lockCanvas(null);
            if (target == null) return false;
            target.drawBitmap(mBitmap, 0, 0, null);
            surface.unlockCanvasAndPost(target);
            target = null;
            onPresented(frame);
            return true;
        } catch (RuntimeException | OutOfMemoryError e) {
            // Surface refused or died mid-present: sequencer must NOT advance.
            Log.w(LOG_TAG, "present failed: " + e);
            return false;
        } finally {
            if (target != null) {
                try {
                    surface.unlockCanvasAndPost(target);
                } catch (RuntimeException ignored) {
                }
            }
            Trace.endSection();
        }
    }

    /** Called only after a confirmed presentation. */
    private void onPresented(TerminalRenderFrame frame) {
        mMetrics.ack(frame.getScreenRevision());
        mLastPresentedFrame = frame;
        // Tag with the backbuffer identity so smoke verifiers can prove the pixel
        // actually flowed through the SurfaceView route.
        TerminalFrameDiagnostics.logIfEnabled(LOG_TAG, mSessionSupplier.get(), mMetrics, frame,
            mRenderStepsSnapshot != null ? mRenderStepsSnapshot : mRenderer.getAndResetRenderStepDelta());
    }
}
