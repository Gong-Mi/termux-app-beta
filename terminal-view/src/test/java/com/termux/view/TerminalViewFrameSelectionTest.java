package com.termux.view;

import static org.junit.Assert.assertSame;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalModelFrame;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class TerminalViewFrameSelectionTest {

    private static TerminalRenderFrame frame() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        TerminalEmulator emulator = new TerminalEmulator(output, 8, 4, 13, 15, 8, null);
        byte[] input = "frame".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        TerminalModelFrame model = new TerminalModelFrame(emulator, 0, null, 0);
        return new TerminalRenderFrame(model, 0, -1, -1, -1, -1);
    }

    @Test
    public void emptyMailboxReusesLastRenderedFrame() {
        TerminalRenderFrame last = frame();
        assertSame(last, TerminalView.frameForDraw(null, last));
    }

    @Test
    public void newMailboxEntryWinsOverLastRenderedFrame() {
        TerminalRenderFrame last = frame();
        TerminalRenderFrame next = frame();
        TerminalFrameConsumerMailbox<TerminalRenderFrame> mailbox =
            new TerminalFrameConsumerMailbox<>(new RenderFrameMetrics(), 1L, 1L);
        TerminalFrameIdentity identity = new TerminalFrameIdentity(1L, 1L, next.screenRevision, 1L);
        mailbox.submit(next, identity);

        assertSame(next, TerminalView.frameForDraw(mailbox.acquireLatest(), last));
    }
}
