package com.termux.view;

import com.termux.terminal.FrameRevision;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fake ASYNCHRONOUS backend consumer for the {@link TerminalFrameConsumer}
 * conformance contract (issue #57 P1): submit() hands frames to a background
 * executor like a Surface/GLES render thread would, attach/detach are
 * generation-gated, and detachAndJoin blocks until the in-flight submit has
 * fully completed or the timeout elapses.
 *
 * <p>This is the reference fake for backend-neutral conformance tests. It
 * defines, by demonstration, the postconditions every asynchronous consumer
 * implementation must honor:</p>
 *
 * <ul>
 *   <li>submit from a foreign generation is rejected without touching the backend;</li>
 *   <li>detachAndJoin(true) means NO frame is still being processed — not merely
 *       that detach() was called;</li>
 *   <li>a false return is retryable; the task keeps ownership until a later join
 *       observes completion;</li>
 *   <li>after a true join the backend receives nothing more, and late acks for
 *       the drained frames still work through the mailbox (identity ladders are
 *       owned by the mailbox, not the consumer).</li>
 * </ul>
 */
public final class FakeAsyncFrameConsumer implements TerminalFrameConsumer {

    /** Minimal immutable frame for contract tests. */
    public static final class ContractFrame implements FrameRevision {
        private final long revision;
        public ContractFrame(long revision) { this.revision = revision; }
        @Override public long getScreenRevision() { return revision; }
    }

    public interface Backend {
        /** Render one frame on the backend thread. Must be the only backend entry point. */
        void render(long revision);
    }

    private final ExecutorService mBackendExecutor;
    private final Backend mBackend;

    private long mGeneration = -1;
    private boolean mAttached;
    private final AtomicBoolean mShutdown = new AtomicBoolean(false);
    /** Frames currently executing on the backend thread (0 or 1 for a serial backend). */
    private final AtomicLong mInFlight = new AtomicLong();
    private final Object mJoinLock = new Object();
    private boolean mBackendCompletedSinceJoinRequest;

    public FakeAsyncFrameConsumer(ExecutorService backendExecutor, Backend backend) {
        mBackendExecutor = backendExecutor;
        mBackend = backend;
    }

    @Override
    public void attach(long renderGeneration, RenderGeometry geometry) {
        // A consumer drained by detachAndJoin can be re-attached to a NEW generation
        // (this is exactly what TerminalView's target rebind does). Only re-attaching
        // the SAME drained generation would resurrect stale in-flight semantics.
        if (mShutdown.get() && renderGeneration == mGeneration) {
            throw new IllegalStateException("consumer is shut down for this generation");
        }
        mShutdown.set(false);
        mGeneration = renderGeneration;
        mAttached = true;
    }

    @Override
    public void submit(TerminalRenderFrame frame, RenderDamage damage,
                       TerminalFrameIdentity identity, long renderGeneration) {
        if (!mAttached) {
            throw new IllegalStateException("not attached");
        }
        if (renderGeneration != mGeneration) {
            // Frame belongs to a detached/foreign generation; ignore like CanvasFrameConsumer.
            return;
        }
        final long revision = frame.getScreenRevision();
        final long submitGeneration = renderGeneration;
        mInFlight.incrementAndGet();
        synchronized (mJoinLock) {
            mBackendCompletedSinceJoinRequest = false;
        }
        try {
            mBackendExecutor.execute(() -> {
                try {
                    // The queued task carries its OWN generation snapshot: a frame that
                    // was legally accepted renders even if a detach/join for that same
                    // generation is concurrently in progress (the join is waiting for
                    // exactly this completion). Generation mismatch (the frame never
                    // belonged to the consumer's current generation) is the only
                    // backend-side rejection — mShutdown only guards NEW submits.
                    if (submitGeneration == mGeneration) {
                        mBackend.render(revision);
                    }
                } finally {
                    mInFlight.decrementAndGet();
                    synchronized (mJoinLock) {
                        mBackendCompletedSinceJoinRequest = true;
                        mJoinLock.notifyAll();
                    }
                }
            });
        } catch (RuntimeException rejected) {
            // Executor rejected the task before any backend work happened.
            mInFlight.decrementAndGet();
            synchronized (mJoinLock) {
                mBackendCompletedSinceJoinRequest = true;
                mJoinLock.notifyAll();
            }
            throw rejected;
        }
    }

    @Override
    public void detach(long renderGeneration) {
        if (renderGeneration != mGeneration) return;
        mAttached = false;
    }

    @Override
    public boolean detachAndJoin(long renderGeneration, long timeoutMs) {
        detach(renderGeneration);
        mShutdown.set(true);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (mJoinLock) {
            while (mInFlight.get() > 0) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    // Timeout: caller keeps ownership and may retry this join.
                    return false;
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(remaining) + 1L;
                try {
                    mJoinLock.wait(Math.min(remainingMs, 50L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return mInFlight.get() == 0;
                }
            }
        }
        mBackendExecutor.shutdownNow();
        return true;
    }

    @Override
    public RenderStats snapshot() {
        return new RenderStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public long attachedGeneration() {
        return mGeneration;
    }

    public boolean isInFlight() {
        return mInFlight.get() > 0;
    }

    /** A single-thread serial backend executor like a dedicated render thread. */
    public static ExecutorService newSerialBackendExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fake-async-backend");
            t.setDaemon(true);
            return t;
        });
    }
}
