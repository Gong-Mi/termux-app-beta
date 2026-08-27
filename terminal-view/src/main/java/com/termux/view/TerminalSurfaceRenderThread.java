package com.termux.view;

import com.termux.terminal.FrameRevision;

/**
 * Surface render THREAD shell for the #52 spike (step 3a). A single command loop
 * owns the {@link TerminalSurfaceGenerationGate} and drives the full-frame
 * {@link TerminalBackbufferSequencer}; pixel mechanics are injected as
 * {@link Backbuffer} so the lifecycle/serialization protocol is JVM-verifiable.
 * The Android slice (3b) supplies the real Bitmap/Canvas + Surface implementation
 * and forwards SurfaceHolder callbacks to onSurfaceCreated/Changed/destroyed.
 *
 * <p>Serialization: one intrinsic lock guards epoch transitions AND cycle
 * execution, which is exactly the Android SurfaceView guarantee surface.Destroyed
 * needs — its caller BLOCKS until the render loop can no longer touch the old
 * surface. In-flight draws finish first; queued cycles after destroy run without
 * touching pixels.</p>
 *
 * <p>Frame flow: producers submit to the mailbox and call requestCycle(); bursts
 * coalesce because a pending-flagged cycle serves whatever is latest when it
 * actually runs (latest-wins end to end).</p>
 */
public final class TerminalSurfaceRenderThread {

    /** Pixel target injected by the Android shell. */
    public interface Backbuffer {
        void resizeTo(int widthPx, int heightPx);

        void drawAll(TerminalRenderFrame frame);

        boolean present();
    }

    private final TerminalSurfaceGenerationGate mGate = new TerminalSurfaceGenerationGate();
    private volatile TerminalFrameConsumerMailbox<TerminalRenderFrame> mMailbox;
    private final Backbuffer mBackbuffer;
    private volatile TerminalBackbufferSequencer mSequencer;
    private final Thread mThread;
    private final Object mLock = new Object();

    private boolean mRunning = true;
    private boolean mCyclePending;
    private long mCurrentEpoch;

    public TerminalSurfaceRenderThread(String name,
                                       TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox,
                                       Backbuffer backbuffer) {
        mMailbox = mailbox;
        mBackbuffer = backbuffer;
        mSequencer = new TerminalBackbufferSequencer(mailbox,
            () -> synchronizedLiveEpoch(), new OpsAdapter());
        mThread = new Thread(this::loop, "termux-surface-render-" + name);
        mThread.setDaemon(false);
    }

    // ---- Surface lifecycle (SurfaceHolder callback side) ----

    /** SurfaceHolder.surfaceCreated. Safe no-op after shutdown. */
    public void onSurfaceCreated() {
        synchronized (mLock) {
            if (!mRunning) return;
            mCurrentEpoch = mGate.created();
            // A fresh Surface buffer starts undefined; if the persistent backbuffer
            // already holds content (activity pause/resume), re-blit it so the
            // screen is never black until the next terminal output. This is a pure
            // re-present: the mailbox slot is left intact and presented markers do
            // not advance — a cycle will still serve the newest pending frame.
            if (mCurrentEpoch != 0L) mBackbuffer.present();
        }
    }

    /** SurfaceHolder.surfaceChanged(width, height) in pixels. */
    public void onSurfaceChanged(int widthPx, int heightPx) {
        synchronized (mLock) {
            if (!mRunning || mCurrentEpoch == 0L) return;
            try {
                mCurrentEpoch = mGate.changed(mCurrentEpoch);
            } catch (IllegalArgumentException staleCallback) {
                return;
            }
            if (mCurrentEpoch == 0L) return;
            // Resize belongs to the pixel target and must be serialized with draws.
            mBackbuffer.resizeTo(widthPx, heightPx);
            // Geometry refresh: re-present existing content onto the resized
            // surface (pure re-present, markers untouched — see onSurfaceCreated);
            // the next cycle re-rasters at full resolution anyway.
            mBackbuffer.present();
        }
    }

    /**
     * SurfaceHolder.surfaceDestroyed: block the caller until the loop can no
     * longer touch this surface (in-flight draw included), then close the epoch.
     *
     * @return false if there was no live epoch to close (out-of-order callback).
     */
    public boolean onSurfaceBlockedDestroy() {
        Thread current = Thread.currentThread();
        boolean wasInterrupted = false;
        while (true) {
            Thread owner;
            synchronized (mLock) {
                if (!mRunning || !mGate.isLive(mCurrentEpoch)) break;
                owner = mCycleOwner;
                if (owner == null && !mCyclePending) {
                    // Nobody drawing, nothing queued that could start: close now.
                    boolean closed = mGate.destroyed(mCurrentEpoch);
                    mCurrentEpoch = 0L;
                    return closed;
                }
            }
            if (owner == current) {
                // destroyed() called from inside a draw would deadlock; refuse.
                return false;
            }
            // Let the owner make progress; check again promptly.
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                wasInterrupted = true;
            }
        }
        if (wasInterrupted) current.interrupt();
        synchronized (mLock) {
            if (mGate.isLive(mCurrentEpoch)) {
                boolean closed = mGate.destroyed(mCurrentEpoch);
                mCurrentEpoch = 0L;
                return closed;
            }
            return mGate.liveEpoch() == 0L;
        }
    }

    // ---- Producer side ----

    /** Start the loop thread. Idempotent; safe to call before any surface callback. */
    public void start() {
        mThread.start();
    }

    /** Ask for one serving of the latest frame; bursts coalesce onto one cycle. */
    public void requestCycle() {
        synchronized (mLock) {
            if (!mRunning || mCyclePending) return;
            mCyclePending = true;
            mLock.notifyAll();
        }
    }

    /**
     * Swap in the mailbox of a newly attached session without killing the loop
     * thread (surface render thread is view-scoped, sessions come and go).
     *
     * <p>Any frame still pending in the OLD mailbox is dropped untouched: it
     * belongs to the previous session/target generation and the sequencer would
     * reject it as stale anyway. The swap is serialized with cycle execution,
     * so no partially-acquired frame can straddle the rebind.</p>
     *
     * @return false after shutdown (rebind refused).
     */
    public boolean rebind(TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox) {
        if (mailbox == null) throw new IllegalArgumentException("mailbox");
        synchronized (mLock) {
            if (!mRunning) return false;
            // Wait out an in-flight cycle so acquireLatest cannot race the swap;
            // cycles are short (full-frame raster) and this only happens on
            // session re-attach.
            while (mCycleOwner != null && mCycleOwner != Thread.currentThread()) {
                try {
                    mLock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            mMailbox = mailbox;
            mSequencer = new TerminalBackbufferSequencer(mailbox,
                () -> synchronizedLiveEpoch(), new OpsAdapter());
            mCyclePending = false;
            return true;
        }
    }

    // ---- Loop ----

    private void loop() {
        while (true) {
            boolean serve;
            synchronized (mLock) {
                while (mRunning && !mCyclePending) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        // Fall through; re-check predicates.
                    }
                }
                if (!mRunning) return;
                mCyclePending = false;
                serve = true;
            }
            if (serve) serveOneCycleLocked();
        }
    }

    private void serveOneCycleLocked() {
        synchronized (mLock) {
            mCycleOwner = Thread.currentThread();
            try {
                mSequencer.step(); // result observable via sequencer markers/tests
            } finally {
                mCycleOwner = null;
                mLock.notifyAll();
            }
        }
    }

    private long synchronizedLiveEpoch() {
        synchronized (mLock) {
            return mGate.liveEpoch();
        }
    }

    /** Pixel adapter bound to the injected backbuffer. */
    private final class OpsAdapter implements TerminalBackbufferSequencer.Ops {
        @Override public void resizeIfNeeded(int width, int height) {
            mBackbuffer.resizeTo(width, height);
        }

        @Override public void drawAll(TerminalRenderFrame frame) {
            mBackbuffer.drawAll(frame);
        }

        @Override public boolean present() {
            return mBackbuffer.present();
        }
    }

    // ---- Shutdown ----

    /** Stop the loop and wait for it. Idempotent. */
    public boolean shutdownAndJoin(long timeoutMs) throws InterruptedException {
        synchronized (mLock) {
            mRunning = false;
            if (mGate.isLive(mCurrentEpoch)) {
                mGate.destroyed(mCurrentEpoch);
                mCurrentEpoch = 0L;
            }
            mLock.notifyAll();
        }
        mThread.join(timeoutMs);
        return !mThread.isAlive();
    }

    public boolean isAlive() {
        return mThread.isAlive();
    }

    private Thread mCycleOwner;
}
