package com.termux.terminal;

import junit.framework.TestCase;
import java.lang.reflect.Proxy;

/** Scrollbar queries may occur after attach but before the first non-zero layout. */
public class ScreenActiveRowsUninitializedTest extends TestCase {
    private TerminalSession session() {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{TerminalSessionClient.class},
            (proxy, method, arguments) -> null);
        return new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
    }

    public void testUninitializedActiveRowsHasTheSameSafeDefaultAsOtherGetters() {
        TerminalSession session = session();
        assertFalse(session.isEmulatorInitialized());
        assertEquals(0, session.getScreenRows());
        assertEquals(0, session.getCursorRow());
        assertEquals(0, session.getScreenActiveRows());
    }

    public void testInitializedActiveRowsReadsTheRealBuffer() {
        TerminalSession session = session();
        session.mEmulator = new TerminalEmulator(session, 10, 4, 8, 16, 100, session.mClient);
        byte[] text = "one\r\ntwo\r\nthree\r\nfour\r\nfive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        session.mEmulator.append(text, text.length);
        assertEquals(session.mEmulator.getScreen().getActiveRows(), session.getScreenActiveRows());
    }
}
