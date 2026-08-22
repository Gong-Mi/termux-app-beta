package com.termux.view;

import java.util.concurrent.atomic.AtomicLong;

/** Identifies the currently attached render session and rejects stale callbacks. */
final class TerminalRenderSessionGate {
    private final AtomicLong mGeneration = new AtomicLong();

    long advance() {
        return mGeneration.incrementAndGet();
    }

    boolean isCurrent(long generation) {
        return generation != 0 && mGeneration.get() == generation;
    }
}
