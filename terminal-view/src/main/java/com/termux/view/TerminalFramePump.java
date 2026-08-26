package com.termux.view;

import com.termux.terminal.FrameRevision;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Push-style consumer pump: forwards the latest accepted entry from a
 * {@link TerminalFrameConsumerMailbox} to a backend sink on a caller-chosen
 * {@link Executor} (a Surface/GLES render thread in production, a manual
 * executor under test). This is the push-path counterpart required by #51's
 * completion condition that both consumption models share the same
 * generation-safe mailbox semantics.
 *
 * <p>Scheduling model mirrors {@link TerminalFrameInvalidationGate}: a single
 * drain may be pending at any time; requestDelivery() while a drain is queued
 * or running is coalesced onto that pending cycle, and a producer running
 * inside a drain asks again so late frames are never lost.</p>
 *
 * <p>Detach semantics: {@link #detach()} only flips flags and runs no executor
 * code, so detached behavior stays deterministic regardless of executor queue
 * depth. A task already queued completes without delivering; frames submitted
 * afterwards are not pushed (their ack ladder simply never advances beyond the
 * implicit ACCEPTED).</p>
 */
public final class TerminalFramePump<T extends FrameRevision> {

    /** Backend-side receiver of acquired entries. Single-method for lambda use. */
    public interface Sink<T extends FrameRevision> {
        void accept(TerminalFrameConsumerMailbox.Entry<T> entry);
    }

    private final TerminalFrameConsumerMailbox<T> mMailbox;
    private final Sink<T> mSink;
    private final Executor mExecutor;
    private final AtomicBoolean mScheduled = new AtomicBoolean(false);
    private volatile boolean mAttached = true;
    /** Number of drain tasks currently RUNNING on the executor. */
    private final AtomicInteger mInFlightDrains = new AtomicInteger();
    /** Signals one completed drain; swapped under join so concurrent drains re-arm it. */
    private CountDownLatch mDrainDone = new CountDownLatch(1);

    public TerminalFramePump(TerminalFrameConsumerMailbox<T> mailbox, Sink<T> sink, Executor executor) {
        mMailbox = mailbox;
        mSink = sink;
        mExecutor = executor;
    }

    /**
     * Ask for one delivery cycle of the latest accepted frame.
     *
     * @throws RuntimeException re-thrown from a rejecting executor; the schedule
     *         flag is reset first so a later call retries cleanly.
     */
    public void requestDelivery() {
        if (!mAttached) return;
        if (!mScheduled.compareAndSet(false, true)) return;
        try {
            mExecutor.execute(this::drain);
        } catch (RuntimeException e) {
            // Clear before propagating so the next request can retry.
            mScheduled.set(false);
            throw e;
        }
    }

    private void drain() {
        mInFlightDrains.incrementAndGet();
        try {
            // Clear before acquiring: a producer during delivery schedules exactly one
            // follow-up cycle instead of being swallowed by our own flag.
            mScheduled.set(false);
            if (!mAttached) return;
            TerminalFrameConsumerMailbox.Entry<T> entry = mMailbox.acquireLatest();
            if (entry == null) return;
            mSink.accept(entry);
        } finally {
            mInFlightDrains.decrementAndGet();
            mDrainDone.countDown();
        }
    }

    /**
     * Stop pushing frames to the sink and wait until no drain task is running.
     *
     * <p>The detach flag is flipped FIRST, so after a {@code true} return the sink
     * is guaranteed to receive nothing more from this pump: queued-but-unstarted
     * drains observe the flag and exit, and any in-flight accept has completed
     * before join returns. Idempotent with plain {@link #detach()}.</p>
     *
     * @return true if joined cleanly within the timeout; false if drains were
     *         still running when the timeout expired (retryable).
     */
    public boolean detachAndJoin(long timeoutMs) {
        mAttached = false;
        mScheduled.set(false);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            CountDownLatch latch = mDrainDone;   // snapshot before checking the count
            if (mInFlightDrains.get() == 0) return true;
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) return mInFlightDrains.get() == 0;
            try {
                latch.await(TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                    TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return mInFlightDrains.get() == 0;
            }
        }
    }

    /** Stop pushing frames to the sink without waiting. Idempotent. */
    public void detach() {
        mAttached = false;
        mScheduled.set(false);
    }

    /** Whether this pump still forwards frames. */
    public boolean isAttached() {
        return mAttached;
    }
}
