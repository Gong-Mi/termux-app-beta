package com.termux.terminal;

import junit.framework.TestCase;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link TerminalSessionClientMainThreadWrapper}.
 *
 * <p>Uses a test {@link TerminalSessionClientMainThreadWrapper.Dispatcher} so the
 * wrapper can be exercised without a real Android {@link android.os.Handler} and
 * {@link android.os.Looper}.</p>
 */
public class TerminalSessionClientMainThreadWrapperTest extends TestCase {

    private static final class TestDispatcher implements TerminalSessionClientMainThreadWrapper.Dispatcher {
        final Queue<Runnable> queue = new ArrayDeque<>();
        boolean accept = true;
        volatile Thread currentThread;

        @Override
        public boolean isCurrentThread() {
            return Thread.currentThread() == currentThread;
        }

        @Override
        public boolean post(Runnable runnable) {
            if (!accept) return false;
            queue.add(runnable);
            return true;
        }

        void runOne() {
            assertFalse("Expected a queued runnable", queue.isEmpty());
            queue.remove().run();
        }
    }

    private static final class CapturingClient implements TerminalSessionClient {
        final AtomicReference<String> textChangedThread = new AtomicReference<>();
        final AtomicReference<String> titleChangedThread = new AtomicReference<>();
        final AtomicReference<String> finishedThread = new AtomicReference<>();
        int textChangedCount;
        int titleChangedCount;
        int finishedCount;

        @Override
        public void onTextChanged(TerminalSession changedSession) {
            textChangedThread.set(Thread.currentThread().getName());
            textChangedCount++;
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
            titleChangedThread.set(Thread.currentThread().getName());
            titleChangedCount++;
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            finishedThread.set(Thread.currentThread().getName());
            finishedCount++;
        }

        @Override public void onCopyTextToClipboard(TerminalSession session, String text) { }
        @Override public void onPasteTextFromClipboard(TerminalSession session) { }
        @Override public void onBell(TerminalSession session) { }
        @Override public void onColorsChanged(TerminalSession session) { }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(TerminalSession session, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) { }
        @Override public void logWarn(String tag, String message) { }
        @Override public void logInfo(String tag, String message) { }
        @Override public void logDebug(String tag, String message) { }
        @Override public void logVerbose(String tag, String message) { }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        @Override public void logStackTrace(String tag, Exception e) { }
    }

    public void testCallbacksRunOnDispatcherThread() {
        TestDispatcher dispatcher = new TestDispatcher();
        CapturingClient client = new CapturingClient();
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(client, dispatcher);

        // Simulate a non-dispatcher caller thread; callbacks must be posted.
        dispatcher.currentThread = new Thread("TestDispatcherThread");

        wrapper.onTextChanged(null);
        wrapper.onTitleChanged(null);
        wrapper.onSessionFinished(null);

        assertEquals("text, title and finish callbacks should each post one runnable",
                3, dispatcher.queue.size());
        dispatcher.runOne();
        dispatcher.runOne();
        dispatcher.runOne();
        assertEquals(1, client.textChangedCount);
        assertEquals(1, client.titleChangedCount);
        assertEquals(1, client.finishedCount);
    }

    public void testCallbacksRunSynchronouslyOnCurrentThread() {
        TestDispatcher dispatcher = new TestDispatcher();
        CapturingClient client = new CapturingClient();
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(client, dispatcher);

        dispatcher.currentThread = Thread.currentThread();

        wrapper.onTextChanged(null);
        wrapper.onTitleChanged(null);
        wrapper.onSessionFinished(null);

        assertTrue("No runnables should be posted when already on dispatcher thread",
                dispatcher.queue.isEmpty());
        assertEquals(1, client.textChangedCount);
        assertEquals(Thread.currentThread().getName(), client.textChangedThread.get());
        assertEquals(1, client.titleChangedCount);
        assertEquals(Thread.currentThread().getName(), client.titleChangedThread.get());
        assertEquals(1, client.finishedCount);
        assertEquals(Thread.currentThread().getName(), client.finishedThread.get());
    }

    public void testTextChangeCoalescing() {
        TestDispatcher dispatcher = new TestDispatcher();
        CapturingClient client = new CapturingClient();
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(client, dispatcher);

        dispatcher.currentThread = new Thread("TestDispatcherThread");

        for (int i = 0; i < 100; i++) {
            wrapper.onTextChanged(null);
        }

        assertEquals("Repeated text-changed calls must share one posted runnable",
                1, dispatcher.queue.size());
        dispatcher.runOne();
        assertEquals(1, client.textChangedCount);
    }

    public void testRejectedPostDoesNotPoisonTextChangeCoalescer() {
        TestDispatcher dispatcher = new TestDispatcher();
        CapturingClient client = new CapturingClient();
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(client, dispatcher);

        dispatcher.currentThread = new Thread("TestDispatcherThread");
        dispatcher.accept = false;

        wrapper.onTextChanged(null);
        assertEquals(0, dispatcher.queue.size());

        dispatcher.accept = true;
        wrapper.onTextChanged(null);
        dispatcher.runOne();
        assertEquals(1, client.textChangedCount);
    }
}
