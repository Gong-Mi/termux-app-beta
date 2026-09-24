package com.termux.view;

/** Runs render work and emits completion only after it returns successfully. */
final class TerminalFrameRenderCompletion {
    private TerminalFrameRenderCompletion() { }

    static void run(Runnable render, Runnable onSuccess) {
        render.run();
        onSuccess.run();
    }
}
