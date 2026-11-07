package com.termux.terminal;

import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/** Logical parser errors must not change the child's independently observed exit status. */
public class SessionFailureStatusTest extends TestCase {
    private TerminalSession observedExit(int status) throws Exception {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{TerminalSessionClient.class}, (p, m, a) -> null);
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        // Synthetic process boundary only: no positive PID, native library or signal.
        session.mShellPid = -1;
        field(session, "mProcessExited", true);
        field(session, "mProcessExitStatus", Integer.valueOf(status));
        return session;
    }
    private void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }
    private void preserves(int status) throws Exception {
        TerminalSession session = observedExit(status);
        RuntimeException cause = new IllegalStateException("parser failed while draining tail output");
        session.onParserWorkerFailure(cause);
        assertSame(cause, session.getFailureCause());
        assertEquals(Integer.valueOf(status), session.getProcessExitStatus());
        assertEquals("logical failure must not overwrite the shell exit status", status, session.getExitStatus());
        assertFalse(session.isRunning());
    }
    public void testObservedExitIsReadableBeforeDeferredCleanup() throws Exception {
        TerminalSession session = observedExit(7);
        assertEquals("published exit fact must not expose the old default", 7, session.getExitStatus());
    }
    public void testLogicalFailurePreservesAZeroChildExit() throws Exception { preserves(0); }
    public void testLogicalFailurePreservesTheRealSignalExit() throws Exception { preserves(-9); }
    public void testUnstartedFailureDoesNotInventAChildExit() {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{TerminalSessionClient.class}, (p, m, a) -> null);
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
        RuntimeException cause = new IllegalStateException("failure before subprocess creation");
        session.onParserWorkerFailure(cause);
        assertSame(cause, session.getFailureCause());
        assertNull("no wait status exists without a child", session.getProcessExitStatus());
        assertFalse(session.isRunning());
    }
}
