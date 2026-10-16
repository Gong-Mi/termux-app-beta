package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import junit.framework.TestCase;

/**
 * RED repro (audit L4-01): getTranscriptText takes the mEmulator monitor BEFORE the
 * null check, so calling it on a session whose emulator was never initialized
 * (updateSize() not yet called) throws NullPointerException instead of returning null.
 *
 * The two neighbours (getWordAtLocation, getSelectedText) check first and lock second;
 * 98d26543 ("guard ALL synchronized(mEmulator) getters") missed this one.
 */
public class TranscriptTextUninitializedTest extends TestCase {

    private static final class NoopClient implements TerminalSessionClient {
        @Override public void onTextChanged(@NonNull TerminalSession session) { }
        @Override public void onSessionFinished(@NonNull TerminalSession session) { }
        @Override public void onTitleChanged(@NonNull TerminalSession session) { }
        @Override public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) { }
        @Override public void onPasteTextFromClipboard(@Nullable TerminalSession session) { }
        @Override public void onBell(@NonNull TerminalSession session) { }
        @Override public void onColorsChanged(@NonNull TerminalSession session) { }
        @Override public void onTerminalCursorStateChange(boolean state) { }
        @Override public void setTerminalShellPid(@NonNull TerminalSession session, int pid) { }
        @Override public Integer getTerminalCursorStyle() { return null; }
        @Override public void logError(String tag, String message) { }
        @Override public void logWarn(String tag, String message) { }
        @Override public void logInfo(String tag, String message) { }
        @Override public void logDebug(String tag, String message) { }
        @Override public void logVerbose(String tag, String message) { }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { }
        @Override public void logStackTrace(String tag, Exception e) { }
    }

    public void testGetTranscriptTextReturnsNullWhenEmulatorUninitialized() {
        TerminalSession session = new TerminalSession(
            "/bin/true", "/", new String[]{"/bin/true"}, new String[]{}, null,
            new NoopClient());
        // No initializeEmulator/updateSize call: mEmulator must still be null.
        assertNull("emulator must be uninitialized", session.getEmulator());

        // Pre-fix: NPE from `synchronized (mEmulator)` with null monitor.
        assertNull(session.getTranscriptText(true, true));
        assertNull(session.getTranscriptText(false, false));
    }
}
