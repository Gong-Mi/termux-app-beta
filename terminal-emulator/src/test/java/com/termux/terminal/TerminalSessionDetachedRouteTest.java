package com.termux.terminal;

import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

/** Real TerminalSession routing under the existing mockable-Android JVM boundary; no PTY. */
public class TerminalSessionDetachedRouteTest extends TestCase {
    private static final class Fixture {
        final TerminalSession session;
        final TerminalEmulator emulator;

        Fixture() {
            TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
                TerminalSessionClient.class.getClassLoader(), new Class<?>[] { TerminalSessionClient.class },
                (proxy, method, args) -> null);
            session = new TerminalSession("/unused", "/unused", new String[0], new String[0], null, client);
            emulator = new TerminalEmulator(session, 40, 4, 8, 16, null, client);
            session.mEmulator = emulator;
            session.setFrameSink(new TerminalFrameSink() {
                @Override public void publishFrame(TerminalModelFrame frame) { }
            });
        }

        void append(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            emulator.append(bytes, bytes.length);
        }

        TerminalModelFrame frame() {
            return new TerminalModelFrame(emulator, 0, null, 0);
        }

        TerminalFrameSink route() throws Exception {
            Field field = TerminalSession.class.getDeclaredField("mFrameSink");
            field.setAccessible(true);
            return (TerminalFrameSink) field.get(session);
        }
    }

    public void testRealDetachDropsOldSnapshotAndReadsCurrentEmulator() throws Exception {
        Fixture f = new Fixture();
        f.append("old");
        f.route().publishFrame(f.frame());
        assertTrue(f.session.getScreenTranscriptText().toString().contains("old"));
        f.session.detachFrameSink();
        assertFalse(f.route().shouldCaptureSnapshot());
        f.append("new\033[?25l\033[?1000h");
        assertTrue("real detach must not serve the cached pre-detach transcript",
            f.session.getScreenTranscriptText().toString().contains("oldnew"));
        assertFalse("cursor mode must come from current emulator", f.session.isCursorEnabled());
        assertTrue("mouse tracking mode must come from current emulator", f.session.isMouseTrackingActive());
        assertEquals(f.emulator.getCursorCol(), f.session.getCursorCol());
        assertEquals(f.emulator.getCumulativeScrollRows(), f.session.getCumulativeScrollRows());
    }

    public void testInFlightOldRouteCannotRepopulateDetachedCache() throws Exception {
        Fixture f = new Fixture();
        f.append("old");
        TerminalFrameSink oldRoute = f.route();
        TerminalModelFrame oldFrame = f.frame();
        oldRoute.publishFrame(oldFrame);
        f.session.detachFrameSink();
        f.append("new");
        oldRoute.publishFrame(oldFrame);
        assertTrue("an in-flight retired route must not restore an old cached frame",
            f.session.getScreenTranscriptText().toString().contains("oldnew"));
    }

    public void testRetiredRouteCannotReplaceReattachedSnapshot() throws Exception {
        Fixture f = new Fixture();
        f.append("old");
        TerminalFrameSink oldRoute = f.route();
        TerminalModelFrame oldFrame = f.frame();
        oldRoute.publishFrame(oldFrame);
        f.session.detachFrameSink();
        f.append("new");
        f.session.setFrameSink(new TerminalFrameSink() {
            @Override public void publishFrame(TerminalModelFrame frame) { }
        });
        f.route().publishFrame(f.frame());
        oldRoute.publishFrame(oldFrame);
        assertTrue("reattachment must retain its own new snapshot",
            f.session.getScreenTranscriptText().toString().contains("oldnew"));
    }
}
