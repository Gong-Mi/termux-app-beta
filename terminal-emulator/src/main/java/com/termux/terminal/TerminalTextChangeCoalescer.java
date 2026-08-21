package com.termux.terminal;

import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces notifications while one callback is waiting in a dispatcher queue. */
final class TerminalTextChangeCoalescer {
    interface Poster {
        boolean post(Runnable runnable);
    }

    private final Poster mPoster;
    private final AtomicBoolean mPosted = new AtomicBoolean(false);

    TerminalTextChangeCoalescer(Poster poster) {
        mPoster = poster;
    }

    void notify(Runnable callback) {
        if (!mPosted.compareAndSet(false, true)) return;

        boolean posted;
        try {
            posted = mPoster.post(() -> {
                mPosted.set(false);
                callback.run();
            });
        } catch (RuntimeException | Error e) {
            mPosted.set(false);
            throw e;
        }
        if (!posted) mPosted.set(false);
    }
}
