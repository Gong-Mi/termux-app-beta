package com.termux.app.terminal;

/** Selects the session that receives clipboard paste input. */
final class ClipboardSessionResolver {
    private ClipboardSessionResolver() {}

    static <T> T resolve(T requestedSession, T currentSession) {
        return requestedSession != null ? requestedSession : currentSession;
    }
}
