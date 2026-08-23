package com.termux.app;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

/**
 * Narrow base-to-terminal-ui bridge for session selection callbacks.
 *
 * <p>The service remains in the base APK while the Activity/session UI and
 * Canvas renderer live in the install-time terminal feature. This interface
 * keeps the service from depending on feature implementation classes.</p>
 */
public interface TermuxSessionActivityClient {

    /** Return the feature-owned terminal session client used by TerminalSession. */
    TermuxTerminalSessionClientBase asTerminalSessionClient();

    /** Select a session in the feature-owned terminal UI. */
    void setCurrentSession(TerminalSession session);

    /** Notify the feature-owned session list that the base service changed. */
    void termuxSessionListNotifyUpdated();
}
