package com.termux.view;

import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces parser-to-View invalidation posts into one pending UI callback. */
final class TerminalFrameInvalidationGate {
    interface Poster {
        boolean post(Runnable runnable);
    }

    private final Poster mPoster;
    private final AtomicBoolean mPending = new AtomicBoolean(false);

    TerminalFrameInvalidationGate(Poster poster) {
        mPoster = poster;
    }

    void request(Runnable invalidation) {
        if (!mPending.compareAndSet(false, true)) return;
        final boolean posted;
        try {
            posted = mPoster.post(() -> {
                // Clear before running so a frame published during invalidation
                // queues exactly one follow-up callback instead of being lost.
                mPending.set(false);
                invalidation.run();
            });
        } catch (RuntimeException e) {
            mPending.set(false);
            throw e;
        }
        if (!posted) mPending.set(false);
    }
}
