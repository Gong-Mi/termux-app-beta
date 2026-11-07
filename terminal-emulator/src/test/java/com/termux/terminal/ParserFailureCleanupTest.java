package com.termux.terminal;

import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Failure containment must terminate resources/state, not merely count a callback. */
public class ParserFailureCleanupTest extends TestCase {
    private static final class Output extends TerminalOutput {
        public void write(byte[] d, int o, int n) { }
        public void titleChanged(String a, String b) { }
        public void onCopyTextToClipboard(String t) { }
        public void onPasteTextFromClipboard() { }
        public void onBell() { }
        public void onColorsChanged() { }
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger finished = new AtomicInteger();
        final AtomicReference<Throwable> escaped = new AtomicReference<>();
        final TerminalSession session;
        final TerminalParserWorker worker;
        Fixture(Throwable fault, boolean throwingLogger) throws Exception {
            TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{TerminalSessionClient.class}, (p, m, a) -> {
                    if (m.getName().equals("onSessionFinished")) finished.incrementAndGet();
                    if (throwingLogger && m.getName().equals("logError"))
                        throw new IllegalStateException("secondary logger failure");
                    return null;
                });
            session = new TerminalSession("/unused", "/", new String[0], new String[0], 100, client);
            TerminalEmulator emulator = new TerminalEmulator(new Output(), 80, 24, 8, 16, 100, client);
            session.mEmulator = emulator;
            worker = new TerminalParserWorker(emulator, session.mProcessToTerminalIOQueue,
                frame -> { if (fault instanceof Error) throw (Error) fault; throw (RuntimeException) fault; },
                client, session, 65536, 32768);
            Field wf = TerminalSession.class.getDeclaredField("mParserWorker"); wf.setAccessible(true); wf.set(session, worker);
            Field tf = TerminalParserWorker.class.getDeclaredField("mThread"); tf.setAccessible(true);
            ((Thread) tf.get(worker)).setUncaughtExceptionHandler((t, e) -> escaped.set(e));
        }
        void fail() throws Exception {
            worker.start();
            byte[] text = new byte[]{'x'};
            assertTrue(session.mProcessToTerminalIOQueue.write(text, 0, text.length));
            worker.requestAppend();
            assertTrue("worker failed to exit", worker.awaitStopped(3000));
        }
        public void close() throws Exception {
            session.mProcessToTerminalIOQueue.close(); session.mTerminalToProcessIOQueue.close();
            worker.stop(); assertTrue("owned worker leaked", worker.awaitStopped(3000));
        }
    }
    public void testSinkFailureClosesQueuesAndRecordsFailureBeforeOneFinish() throws Exception {
        try (Fixture f = new Fixture(new IllegalStateException("sink failure"), false)) {
            f.fail();
            assertNull("ordinary failure escaped", f.escaped.get());
            assertEquals(1, f.finished.get());
            assertFalse(f.session.isRunning());
            assertTrue("failed session was reported as successful", f.session.getExitStatus() != 0);
            assertFalse("inbound remains writable after worker death", f.session.mProcessToTerminalIOQueue.write(new byte[]{1}, 0, 1));
            assertFalse("outbound remains writable after worker death", f.session.mTerminalToProcessIOQueue.write(new byte[]{1}, 0, 1));
            f.worker.requestFinish(0);
            f.worker.requestAppend();
            assertEquals("late finish duplicated completion", 1, f.finished.get());
        }
    }
    public void testSecondaryLoggingFailureCannotEscapeOrSkipCleanup() throws Exception {
        try (Fixture f = new Fixture(new IllegalStateException("sink failure"), true)) {
            f.fail();
            assertNull("logging failure escaped containment", f.escaped.get());
            assertFalse(f.session.mProcessToTerminalIOQueue.write(new byte[]{1}, 0, 1));
            assertEquals(1, f.finished.get());
        }
    }
    public void testVirtualMachineErrorIsNotMisrepresentedAsRecoveredSession() throws Exception {
        try (Fixture f = new Fixture(new OutOfMemoryError("synthetic fatal VM error; no real memory pressure"), false)) {
            f.fail();
            assertTrue("fatal VM error was swallowed", f.escaped.get() instanceof VirtualMachineError);
            assertEquals("fatal VM error falsely reported as isolated recovery", 0, f.finished.get());
        }
    }
}
