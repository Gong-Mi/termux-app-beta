package com.termux.view;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Surface lifecycle generation gate for the #52 SurfaceView backbuffer route.
 * Models one SurfaceView's created/changed/destroyed cycle as a monotonic epoch
 * counter guarded by a read-write lock, so a render thread can snapshot the live
 * epoch before scheduling work and re-validate under the lock at draw time.
 *
 * Semantics (mirrors Android SurfaceHolder callbacks):
 * - {@link #created()} opens a new epoch; refused if one is already open (the
 *   platform cannot deliver created twice without destroyed in between);
 * - {@link #changed(long)} resizes/reconfigures the CURRENT epoch: the old epoch
 *   dies, a fresh one is issued (old-geometry frames must not draw);
 * - {@link #destroyed(long)} closes an epoch; destroying anything but the live
 *   epoch returns false — stale/out-of-order destroy callbacks are treated as a
 *   bug signal (kill-criterion evidence), never silently ignored;
 * - after destroy there is no live epoch until the next created().
 *
 * Draw-time protocol: callers take their epoch once (from created/changed) and
 * call {@link #beginDraw(long)} under this lock per frame. beginDraw doubles as
 * the hold point for detach/join ordering: a surface destroy must join the
 * render thread BEFORE the next surface's first draw can acquire the gate.
 */
public final class TerminalSurfaceGenerationGate {

    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();
    private final AtomicLong mEpochCounter = new AtomicLong();
    private long mLiveEpoch;

    /** Epoch currently owning the surface; 0 = none (detached). */
    public long liveEpoch() {
        mLock.readLock().lock();
        try {
            return mLiveEpoch;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Whether {@code epoch} is the live one right now (lock-free check). */
    public boolean isLive(long epoch) {
        return epoch != 0L && epoch == liveEpoch();
    }

    /**
     * SurfaceHolder.surfaceCreated: open a new epoch.
     *
     * @throws IllegalStateException if an epoch is already open (double-create).
     */
    public long created() {
        mLock.writeLock().lock();
        try {
            if (mLiveEpoch != 0L) {
                throw new IllegalStateException("surfaceCreated while epoch " + mLiveEpoch + " is still open");
            }
            mLiveEpoch = mEpochCounter.incrementAndGet();
            return mLiveEpoch;
        } finally {
            mLock.writeLock().unlock();
        }
    }

    /**
     * SurfaceHolder.surfaceChanged: reconfigure the CURRENT epoch into a new one.
     *
     * @throws IllegalArgumentException if {@code expected} is not the live epoch.
     */
    public long changed(long expected) {
        mLock.writeLock().lock();
        try {
            if (expected == 0L || expected != mLiveEpoch) {
                throw new IllegalArgumentException(
                    "surfaceChanged for non-live epoch " + expected + " (live=" + mLiveEpoch + ")");
            }
            mLiveEpoch = mEpochCounter.incrementAndGet();
            return mLiveEpoch;
        } finally {
            mLock.writeLock().unlock();
        }
    }

    /** Cheap per-frame validation that a task's epoch may still draw. */
    public boolean canDraw(long epoch) {
        return isLive(epoch);
    }

    /**
     * Draw-time admission under the write lock. While held, a concurrent
     * destroyed() on the same epoch cannot complete, which gives destroy-side
     * join its ordering point.
     */
    public boolean beginDraw(long epoch) {
        mLock.readLock().lock();
        try {
            return mLiveEpoch != 0L && mLiveEpoch == epoch;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /**
     * SurfaceHolder.surfaceDestroyed: close exactly the live epoch.
     *
     * @return false (bug signal) if {@code epoch} is not the current live epoch.
     */
    public boolean destroyed(long epoch) {
        mLock.writeLock().lock();
        try {
            if (epoch == 0L || epoch != mLiveEpoch) {
                return false;
            }
            mLiveEpoch = 0L;
            return true;
        } finally {
            mLock.writeLock().unlock();
        }
    }
}
