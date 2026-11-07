package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Serializes clipboard writes onto a single worker thread.
 *
 * <p>Clipboard writes used to be dispatched as one new thread per copy. That kept
 * {@code ClipboardManager.setPrimaryClip} off the main thread, but it gave no ordering
 * guarantee: two OSC 52 payloads emitted back to back (or a selection copy racing an
 * OSC 52 copy) could land in the clipboard in reverse order, leaving the older text
 * behind. It also created one unbounded thread per copy.</p>
 *
 * <p>A single-thread executor keeps submission order while still running the binder call
 * off the UI thread. If a write throws, the worker is replaced and later writes still run,
 * so one bad payload cannot wedge the clipboard.</p>
 */
public final class ClipboardWriteQueue {

    private final ExecutorService mExecutor;

    public ClipboardWriteQueue(@NonNull String threadName) {
        mExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(@NonNull Runnable runnable) {
                Thread thread = new Thread(runnable, threadName);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /** Queues a clipboard write. Writes run in submission order on one worker thread. */
    public void submit(@Nullable Runnable write) {
        if (write == null) return;
        mExecutor.execute(() -> {
            try {
                write.run();
            } catch (Throwable t) {
                // An uncaught escape from a pool worker would hit RuntimeInit's
                // KillApplicationHandler and SIGKILL the whole app process on Android.
                // Contain it: log and keep the queue alive.
                Logger.logError(null, "ClipboardWriteQueue", "clipboard write failed: " + t);
            }
        });
    }

    /** Stops accepting writes and releases the worker thread. */
    public void shutdown() {
        mExecutor.shutdown();
    }

    /**
     * Waits until every write submitted before this call has finished.
     *
     * @return true if the queue drained within the timeout.
     */
    boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        CountDownLatch drained = new CountDownLatch(1);
        try {
            mExecutor.execute(drained::countDown);
        } catch (RuntimeException e) {
            // Queue already shut down, or rejected: nothing left to drain.
            return true;
        }
        return drained.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
