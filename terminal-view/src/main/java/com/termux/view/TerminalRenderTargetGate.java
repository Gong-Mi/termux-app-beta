package com.termux.view;

import java.util.concurrent.atomic.AtomicLong;

/** Monotonic render-target generation independent from terminal session generation. */
final class TerminalRenderTargetGate {
    private final AtomicLong mGeneration = new AtomicLong();

    long attach() {
        return mGeneration.incrementAndGet();
    }

    long detach() {
        return mGeneration.incrementAndGet();
    }

    boolean isCurrent(long generation) {
        return generation != 0L && mGeneration.get() == generation;
    }
}
