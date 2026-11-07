package com.termux.terminal;

/** Local failure reporting must not replace an ordinary fault with a logging crash. */
final class TerminalFailureUtils {
    private TerminalFailureUtils() { }
    static void rethrowFatal(Throwable error) {
        if (error instanceof VirtualMachineError) throw (VirtualMachineError) error;
        if (error instanceof ThreadDeath) throw (ThreadDeath) error;
    }
    static void log(TerminalSessionClient client, String tag, String message, Throwable error) {
        try {
            Logger.logStackTraceWithMessage(client, tag, message, error);
        } catch (Throwable loggingError) {
            rethrowFatal(loggingError);
            try {
                Logger.logError(null, tag, message + " (client logging failed)");
            } catch (Throwable fallbackError) {
                rethrowFatal(fallbackError);
            }
        }
    }
}
