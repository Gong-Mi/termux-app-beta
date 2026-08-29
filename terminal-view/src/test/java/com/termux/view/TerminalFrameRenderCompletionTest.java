package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class TerminalFrameRenderCompletionTest {

    @Test
    public void completionCallbackRunsOnlyAfterRenderSucceeds() {
        int[] callbacks = {0};
        TerminalFrameRenderCompletion.run(() -> { }, () -> callbacks[0]++);
        assertEquals(1, callbacks[0]);
    }

    @Test
    public void completionCallbackDoesNotRunWhenRenderFails() {
        int[] callbacks = {0};
        try {
            TerminalFrameRenderCompletion.run(() -> {
                throw new IllegalStateException("render failed");
            }, () -> callbacks[0]++);
            fail("render failure must propagate");
        } catch (IllegalStateException expected) {
            assertEquals(0, callbacks[0]);
        }
    }
}
