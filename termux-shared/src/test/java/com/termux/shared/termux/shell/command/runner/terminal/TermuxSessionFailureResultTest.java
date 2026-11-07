package com.termux.shared.termux.shell.command.runner.terminal;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.terminal.TerminalSession;
import junit.framework.TestCase;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** Consumer-level contracts: a parser error is an internal error, not a forged exit code. */
public class TermuxSessionFailureResultTest extends TestCase {
    private void field(TerminalSession session, String name, Object value) throws Exception {
        Field field = TerminalSession.class.getDeclaredField(name);
        field.setAccessible(true); field.set(session, value);
    }
    private TermuxSession session(Integer actualStatus, Throwable failure, AtomicInteger callbacks) throws Exception {
        // This module deliberately does not mock Android Loopers. Allocate a metadata-only
        // process-boundary fixture; constructor/Looper behavior is covered by the ART gate.
        Class<?> allocatorType = Class.forName("sun.misc.Unsafe");
        Field allocatorField = allocatorType.getDeclaredField("theUnsafe");
        allocatorField.setAccessible(true);
        TerminalSession terminal = (TerminalSession) allocatorType.getMethod("allocateInstance", Class.class)
            .invoke(allocatorField.get(null), TerminalSession.class);
        // Never assigns a positive PID or calls JNI.
        field(terminal, "mShellPid", -1);
        field(terminal, "mProcessExitStatus", actualStatus);
        if (actualStatus != null) field(terminal, "mShellExitStatus", actualStatus);
        field(terminal, "mFailureCause", failure);
        ExecutionCommand command = new ExecutionCommand();
        assertTrue(command.setState(ExecutionCommand.ExecutionState.EXECUTING));
        Constructor<TermuxSession> constructor = TermuxSession.class.getDeclaredConstructor(
            TerminalSession.class, ExecutionCommand.class, TermuxSession.TermuxSessionClient.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(terminal, command,
            (TermuxSession.TermuxSessionClient) exited -> callbacks.incrementAndGet(), false);
    }
    private void failureResult(Integer status) throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        TermuxSession session = session(status, new IllegalStateException("parser failed"), callbacks);
        session.finish();
        ExecutionCommand command = session.getExecutionCommand();
        assertEquals("actual child status must survive into the result", status, command.resultData.exitCode);
        assertTrue("logical session failure must be published as an internal command error", command.isStateFailed());
        assertTrue(command.resultData.isStateFailed());
        assertTrue(command.resultData.getErrCode() != 0);
        assertEquals(1, callbacks.get());
        session.finish();
        assertEquals("failure result must not be delivered twice", 1, callbacks.get());
    }
    public void testChildExitZeroAndParserFailureAreNotReportedAsSuccess() throws Exception { failureResult(0); }
    public void testRealSignalExitIsNotReplacedByLogicalErrorCode() throws Exception { failureResult(-9); }
    public void testNoChildExitRemainsUnknownInsteadOfInventingZeroOrSeventy() throws Exception { failureResult(null); }
    public void testHealthyExitResultIsUnchanged() throws Exception {
        for (int status : new int[]{0, 7}) {
            AtomicInteger callbacks = new AtomicInteger();
            TermuxSession session = session(status, null, callbacks);
            session.finish();
            assertEquals(Integer.valueOf(status), session.getExecutionCommand().resultData.exitCode);
            assertFalse(session.getExecutionCommand().isStateFailed());
            assertEquals(1, callbacks.get());
        }
    }
}
