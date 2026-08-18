package com.termux.terminal;

/**
 * Marker for objects that carry a terminal screen revision, allowing generic
 * mailbox and metrics code to reason about frames without depending on concrete
 * view-layer frame types.
 */
public interface FrameRevision {
    long getScreenRevision();
}
