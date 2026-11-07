package com.termux.terminal;

import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** A model/queued callback can retain an endpoint across the actual Session setter. */
public class SessionClientHandoffIntegrationTest extends TestCase {
    private TerminalSessionClient client(AtomicInteger finished) {
        return (TerminalSessionClient) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{TerminalSessionClient.class}, (p, m, a) -> {
                if (m.getName().equals("onSessionFinished")) finished.incrementAndGet();
                return null;
            });
    }
    private TerminalSessionClient endpoint(TerminalSession session) throws Exception {
        Field field = TerminalSession.class.getDeclaredField("mEmulatorClient");
        field.setAccessible(true);
        return (TerminalSessionClient) field.get(session);
    }
    public void testRetainedEndpointUsesClientInstalledByRealSessionSetter() throws Exception {
        AtomicInteger a = new AtomicInteger(), b = new AtomicInteger();
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client(a));
        TerminalSessionClient retained = endpoint(session);
        session.updateTerminalSessionClient(client(b));
        retained.onSessionFinished(session);
        assertEquals("retired client received completion", 0, a.get());
        assertEquals("new client missed retained-endpoint completion", 1, b.get());
        assertSame("session must keep one endpoint", retained, endpoint(session));
    }
    public void testRepeatedRealSessionHandoffsKeepRetainedEndpointCurrent() throws Exception {
        AtomicInteger a = new AtomicInteger(), b = new AtomicInteger(), c = new AtomicInteger();
        TerminalSession session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client(a));
        TerminalSessionClient retained = endpoint(session);
        session.updateTerminalSessionClient(client(b));
        session.updateTerminalSessionClient(client(c));
        retained.onSessionFinished(session);
        assertEquals(0, a.get()); assertEquals(0, b.get()); assertEquals(1, c.get());
    }
}
