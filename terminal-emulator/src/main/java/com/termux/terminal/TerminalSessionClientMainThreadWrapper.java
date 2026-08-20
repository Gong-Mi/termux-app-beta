package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Wraps a {@link TerminalSessionClient} so that all callbacks are delivered on the
 * looper passed to the constructor (typically the main thread).
 *
 * <p>This is the bridge used by the parser worker thread: it can call client methods
 * directly, and the wrapper ensures that the real client receives them on the UI
 * thread. Logging methods are delegated synchronously to avoid flooding the handler
 * queue.</p>
 */
public final class TerminalSessionClientMainThreadWrapper implements TerminalSessionClient {

    private final TerminalSessionClient mClient;
    private final Handler mHandler;
    private final AtomicBoolean mTextChangePosted = new AtomicBoolean(false);

    public TerminalSessionClientMainThreadWrapper(@NonNull TerminalSessionClient client, @NonNull Handler handler) {
        mClient = client;
        mHandler = handler;
    }

    private void post(Runnable r) {
        if (mHandler.getLooper() == Looper.myLooper()) {
            r.run();
        } else {
            mHandler.post(r);
        }
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (mHandler.getLooper() == Looper.myLooper()) {
            mClient.onTextChanged(changedSession);
            return;
        }
        if (!mTextChangePosted.compareAndSet(false, true)) return;
        boolean posted = mHandler.post(() -> {
            mTextChangePosted.set(false);
            mClient.onTextChanged(changedSession);
        });
        if (!posted) mTextChangePosted.set(false);
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        post(() -> mClient.onTitleChanged(changedSession));
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        post(() -> mClient.onSessionFinished(finishedSession));
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        post(() -> mClient.onCopyTextToClipboard(session, text));
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        post(() -> mClient.onPasteTextFromClipboard(session));
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        post(() -> mClient.onBell(session));
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession session) {
        post(() -> mClient.onColorsChanged(session));
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        post(() -> mClient.onTerminalCursorStateChange(state));
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession session, int pid) {
        post(() -> mClient.setTerminalShellPid(session, pid));
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return mClient.getTerminalCursorStyle();
    }

    @Override
    public void logError(String tag, String message) {
        mClient.logError(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        mClient.logWarn(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        mClient.logInfo(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        mClient.logDebug(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        mClient.logVerbose(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        mClient.logStackTraceWithMessage(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        mClient.logStackTrace(tag, e);
    }
}
