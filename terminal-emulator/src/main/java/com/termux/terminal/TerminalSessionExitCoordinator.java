package com.termux.terminal;

/**
 * Main-thread state for coordinating process exit with completion of the PTY input reader.
 * Cleanup is safe only after the process exited, the reader finished (or its grace period expired),
 * and all bytes already queued for the terminal emulator were drained.
 */
final class TerminalSessionExitCoordinator {

    private boolean mProcessExited;
    private boolean mReaderFinished;
    private boolean mReaderTimedOut;
    private boolean mFinished;
    private int mExitStatus;

    /**
     * Record process completion.
     *
     * @return whether a reader-completion timeout should be scheduled.
     */
    boolean markProcessExited(int exitStatus) {
        if (mProcessExited) return false;
        mProcessExited = true;
        mExitStatus = exitStatus;
        return !mReaderFinished;
    }

    void markReaderFinished() {
        mReaderFinished = true;
    }

    void markReaderTimeout() {
        mReaderTimedOut = true;
    }

    boolean shouldFinish(boolean hasQueuedInput) {
        return !mFinished && mProcessExited && !hasQueuedInput && (mReaderFinished || mReaderTimedOut);
    }

    void markFinished() {
        mFinished = true;
    }

    int getExitStatus() {
        if (!mProcessExited) throw new IllegalStateException("Process has not exited");
        return mExitStatus;
    }
}
