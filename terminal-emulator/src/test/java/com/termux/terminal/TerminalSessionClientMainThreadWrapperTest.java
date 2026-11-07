package com.termux.terminal;

import junit.framework.TestCase;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

        void runAll() {
            while (!queue.isEmpty()) runOne();
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

    /** Records callbacks and log calls so tests can verify which client received what, and when. */
    private static final class RoutingClient implements TerminalSessionClient {
        final List<String> events = new ArrayList<>();
        int textChangedCount;
        int titleChangedCount;
        int finishedCount;
        Integer cursorStyle;

        /** One-shot action run from inside {@link #onTextChanged}. */
        Runnable textChangedAction;
        /** One-shot action run from inside {@link #onTitleChanged}. */
        Runnable titleChangedAction;

        private void record(String event) {
            events.add(event);
        }

        @Override
        public void onTextChanged(TerminalSession changedSession) {
            record("text");
            textChangedCount++;
            Runnable action = textChangedAction;
            if (action != null) {
                textChangedAction = null;
                action.run();
            }
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
            record("title");
            titleChangedCount++;
            Runnable action = titleChangedAction;
            if (action != null) {
                titleChangedAction = null;
                action.run();
            }
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            record("finish");
            finishedCount++;
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) { record("copy"); }
        @Override
        public void onPasteTextFromClipboard(TerminalSession session) { record("paste"); }
        @Override
        public void onBell(TerminalSession session) { record("bell"); }
        @Override
        public void onColorsChanged(TerminalSession session) { record("colors"); }
        @Override
        public void onTerminalCursorStateChange(boolean state) { record("cursor:" + state); }
        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) { record("pid:" + pid); }
        @Override
        public Integer getTerminalCursorStyle() { return cursorStyle; }
        @Override
        public void logError(String tag, String message) { record("logError:" + tag); }
        @Override
        public void logWarn(String tag, String message) { record("logWarn:" + tag); }
        @Override
        public void logInfo(String tag, String message) { record("logInfo:" + tag); }
        @Override
        public void logDebug(String tag, String message) { record("logDebug:" + tag); }
        @Override
        public void logVerbose(String tag, String message) { record("logVerbose:" + tag); }
        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) { record("logStackWithMessage:" + tag); }
        @Override
        public void logStackTrace(String tag, Exception e) { record("logStack:" + tag); }
    }

    private static void assertEvents(RoutingClient client, String... expected) {
        assertEquals(Arrays.asList(expected), client.events);
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

    /** A callback queued before a client handoff must be delivered to the client installed
     *  at dispatch time, not to the client that was current when it was posted. */
    public void testQueuedCallbacksRouteToClientInstalledBeforeDispatch() {
        TestDispatcher dispatcher = new TestDispatcher();
        RoutingClient a = new RoutingClient();
        RoutingClient b = new RoutingClient();
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(a, dispatcher);

        dispatcher.currentThread = new Thread("TestDispatcherThread");

        wrapper.onTextChanged(null);
        wrapper.onSessionFinished(null);

        wrapper.updateClient(b);
        assertEquals("Queued callbacks must not be re-posted by the update", 2, dispatcher.queue.size());

        dispatcher.runAll();

        assertEvents(b, "text", "finish");
        assertEvents(a);
        assertEquals(0, a.finishedCount);
        assertEquals(1, b.finishedCount);
    }

    /** Logging and synchronous queries must observe the latest client immediately, without posting. */
    public void testLoggingAndCursorStyleUseLatestClientSynchronously() {
        TestDispatcher dispatcher = new TestDispatcher();
        RoutingClient a = new RoutingClient();
        RoutingClient b = new RoutingClient();
        a.cursorStyle = 1;
        b.cursorStyle = 2;
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(a, dispatcher);

        // Simulate calls from the parser worker: not the dispatcher thread.
        dispatcher.currentThread = new Thread("TestDispatcherThread");

        wrapper.logInfo("BeforeUpdate", "message");
        wrapper.updateClient(b);
        wrapper.logWarn("AfterUpdate", "message");

        assertTrue("Logging must not be posted to the handler", dispatcher.queue.isEmpty());
        assertEvents(a, "logInfo:BeforeUpdate");
        assertEvents(b, "logWarn:AfterUpdate");
        assertEquals(Integer.valueOf(2), wrapper.getTerminalCursorStyle());
    }

    /** Several handoffs between posting and dispatch must still deliver the finish exactly once,
     *  to the most recently installed client. */
    public void testRepeatedClientUpdatesDoNotRedispatchQueuedFinish() {
        TestDispatcher dispatcher = new TestDispatcher();
        RoutingClient a = new RoutingClient();
        RoutingClient b = new RoutingClient();
        RoutingClient c = new RoutingClient();
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(a, dispatcher);

        dispatcher.currentThread = new Thread("TestDispatcherThread");

        wrapper.onSessionFinished(null);
        wrapper.updateClient(b);
        wrapper.updateClient(c);
        wrapper.updateClient(b);

        assertEquals(1, dispatcher.queue.size());
        dispatcher.runAll();

        assertEquals(1, b.finishedCount);
        assertEquals(0, a.finishedCount);
        assertEquals(0, c.finishedCount);
        assertEquals("The finish must not be duplicated by client updates",
                1, a.finishedCount + b.finishedCount + c.finishedCount);
    }

    /** Coalescing state must survive handoffs: a pending text callback plus later notifications
     *  still produce one runnable, delivered to the newest client. */
    public void testTextChangeCoalescingSurvivesClientUpdate() {
        TestDispatcher dispatcher = new TestDispatcher();
        RoutingClient a = new RoutingClient();
        RoutingClient b = new RoutingClient();
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(a, dispatcher);

        dispatcher.currentThread = new Thread("TestDispatcherThread");

        wrapper.onTextChanged(null);
        wrapper.onTextChanged(null);
        wrapper.updateClient(b);
        for (int i = 0; i < 10; i++) {
            wrapper.onTextChanged(null);
        }

        assertEquals("One pending text callback must coalesce all notifications", 1, dispatcher.queue.size());
        dispatcher.runAll();

        assertEquals(1, b.textChangedCount);
        assertEquals(0, a.textChangedCount);
    }

    /** All queued callback kinds keep their order and each is routed using the client current
     *  when that individual runnable executes. */
    public void testQueuedCallbackBatchPreservesOrderAndRoutesToLatestClient() {
        TestDispatcher dispatcher = new TestDispatcher();
        RoutingClient a = new RoutingClient();
        RoutingClient b = new RoutingClient();
        TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(a, dispatcher);

        dispatcher.currentThread = new Thread("TestDispatcherThread");

        wrapper.onTextChanged(null);
        wrapper.onTitleChanged(null);
        wrapper.onBell(null);
        wrapper.onColorsChanged(null);
        wrapper.onTerminalCursorStateChange(true);
        wrapper.setTerminalShellPid(null, 42);
        wrapper.onCopyTextToClipboard(null, "text");
        wrapper.onPasteTextFromClipboard(null);
        wrapper.onSessionFinished(null);

        assertEquals(9, dispatcher.queue.size());
        wrapper.updateClient(b);
        dispatcher.runAll();

        assertEvents(b, "text", "title", "bell", "colors", "cursor:true", "pid:42",
                "copy", "paste", "finish");
        assertEvents(a);
    }

    /** A callback may hand off to a new client; callbacks queued behind it must use the
     *  new client when their turn comes. */
    public void testUpdateFromInsideCallbackRoutesRemainingQueueToNewClient() {
        TestDispatcher dispatcher = new TestDispatcher();
        RoutingClient a = new RoutingClient();
        final RoutingClient b = new RoutingClient();
        final TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(a, dispatcher);

        dispatcher.currentThread = new Thread("TestDispatcherThread");
        a.titleChangedAction = () -> wrapper.updateClient(b);

        wrapper.onTitleChanged(null);
        wrapper.onBell(null);
        dispatcher.runAll();

        assertEvents(a, "title");
        assertEvents(b, "bell");
    }

    /** The coalescer must be re-armed before the delivered callback runs, so a reentrant
     *  notification is posted again and later routed to the client installed meanwhile. */
    public void testReentrantTextChangeDuringDispatchIsNotSwallowed() {
        TestDispatcher dispatcher = new TestDispatcher();
        RoutingClient a = new RoutingClient();
        final RoutingClient b = new RoutingClient();
        final TerminalSessionClientMainThreadWrapper wrapper =
                new TerminalSessionClientMainThreadWrapper(a, dispatcher);

        dispatcher.currentThread = new Thread("TestDispatcherThread");
        a.textChangedAction = () -> {
            wrapper.updateClient(b);
            wrapper.onTextChanged(null);
        };

        wrapper.onTextChanged(null);
        dispatcher.runOne();

        assertEquals(1, a.textChangedCount);
        assertEquals(0, b.textChangedCount);
        assertEquals("Reentrant notification must post a fresh runnable", 1, dispatcher.queue.size());

        dispatcher.runOne();
        assertEquals(1, a.textChangedCount);
        assertEquals(1, b.textChangedCount);
        assertTrue(dispatcher.queue.isEmpty());
    }
}
