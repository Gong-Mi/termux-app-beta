package com.termux.terminal;

import junit.framework.TestCase;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

/**
 * Process-less boundary of the real {@link TerminalSession} input surface.
 *
 * <p>Without a child process there is no parser worker and {@code isRunning()}
 * is false, so the session must reject input synchronously instead of queueing
 * bytes that can never be delivered. Actual PTY admission, backpressure and
 * failure teardown require a real child and are outside this JVM fixture.</p>
 */
public class TerminalSessionNoProcessOrderedInputTest extends TestCase {

    public void testWriteAndPasteWithoutChildAreRejectedWithoutQueueing() {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(),
            new Class<?>[] { TerminalSessionClient.class },
            (p, m, a) -> null);
        TerminalSession session = new TerminalSession(
            "/unused", "/unused", new String[0], new String[0], null, client);

        assertFalse("fixture must not own a process", session.isRunning());
        assertEquals(0, session.getPid());

        session.write("K".getBytes(StandardCharsets.UTF_8), 0, 1);
        session.paste("paste text");

        assertFalse("rejected key bytes must not enter the outbound queue",
            session.mTerminalToProcessIOQueue.hasData());
        assertEquals(0, session.mTerminalToProcessIOQueue.availableBytes());
        assertNull("dropping input for a dead session is not a new failure", session.getFailureCause());
    }

    public void testZeroLengthWriteIsANoOpWithoutChild() {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(),
            new Class<?>[] { TerminalSessionClient.class },
            (p, m, a) -> null);
        TerminalSession session = new TerminalSession(
            "/unused", "/unused", new String[0], new String[0], null, client);

        session.write(new byte[0], 0, 0);
        assertFalse(session.mTerminalToProcessIOQueue.hasData());
        assertNull(session.getFailureCause());
    }
}
