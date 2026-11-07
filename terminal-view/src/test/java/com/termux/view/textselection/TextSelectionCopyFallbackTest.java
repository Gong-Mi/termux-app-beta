package com.termux.view.textselection;

import static org.junit.Assert.assertEquals;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalRenderFrame;
import com.termux.view.TerminalView;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Copy must not depend on which of the two getSelectedText() branches runs: the frame path
 * (TerminalScreenSnapshot, joinBackLines=true) and the session fallback must share the same
 * WYSIWYG rule - soft-wrapped rows copy without an inserted '\n'.
 *
 * Regression for audit L2-3: the fallback called TerminalSession#getSelectedText with
 * false, and despite the parameter then being named "rectangular" it flows into
 * TerminalBuffer's joinBackLines slot, so the two branches returned different text for the
 * same selection ("abcdefghijXYZ" vs "abcdefghij\nXYZ").
 *
 * Android surface is bypassed with Unsafe allocation plus field injection (same pattern as
 * TextSelectionLifecycleHideTest); all copy/selection logic under test is production code.
 */
public class TextSelectionCopyFallbackTest {

    private static final Object ALLOCATOR;
    private static final Method ALLOCATE;

    static {
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            Field f = type.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            ALLOCATOR = f.get(null);
            ALLOCATE = type.getMethod("allocateInstance", Class.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        return ALLOCATE.invoke(ALLOCATOR, type);
    }

    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException next) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }

    /** A 5x3 emulator whose single logical line "ABCDEFGHIJ" soft-wraps into "ABCDE" + "FGHIJ". */
    private static TerminalEmulator emulatorWithWrappedRow() {
        TerminalOutput output = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) { }
            @Override public void titleChanged(String oldTitle, String newTitle) { }
            @Override public void onCopyTextToClipboard(String text) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        TerminalEmulator emulator = new TerminalEmulator(output, 5, 3, 13, 15, 6, null);
        byte[] input = "ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);
        return emulator;
    }

    /**
     * The production TerminalSession constructor wraps the client with a main-thread
     * Handler/Looper that this module's unit tests do not mock; the copy path under test
     * only reads the emulator field, so allocate without running the constructor.
     */
    private static TerminalSession sessionWithEmulator(TerminalEmulator emulator) throws Exception {
        TerminalSession session = (TerminalSession) allocate(TerminalSession.class);
        set(session, "mEmulator", emulator);
        return session;
    }

    /** Controller wired to a view state: select rows 0..1, columns 0..4. */
    private static TextSelectionCursorController controllerForRows01(TerminalSession session,
                                                                     TerminalRenderFrame frame) throws Exception {
        TerminalView view = (TerminalView) allocate(TerminalView.class);
        view.mTermSession = session;
        set(view, "mLastRenderFrame", frame);

        TextSelectionCursorController c = (TextSelectionCursorController) allocate(TextSelectionCursorController.class);
        set(c, "terminalView", view);
        set(c, "mSelX1", 0);
        set(c, "mSelY1", 0);
        set(c, "mSelX2", 4);
        set(c, "mSelY2", 1);
        return c;
    }

    @Test
    public void fallbackCopyWithNoFrameMustJoinWrappedRows() throws Exception {
        TerminalEmulator emulator = emulatorWithWrappedRow();
        // Fixture sanity: with joinBackLines=false the buffer inserts '\n' inside the wrapped line.
        assertEquals("ABCDE\nFGHIJ", emulator.getScreen().getSelectedText(0, 0, 4, 1, false));

        TextSelectionCursorController c = controllerForRows01(sessionWithEmulator(emulator), null);
        // WYSIWYG: one visual line must copy as one string. Pre-fix the fallback inserted '\n'.
        assertEquals("ABCDEFGHIJ", c.getSelectedText());
    }

    @Test
    public void copyTextMustNotDependOnFrameAvailability() throws Exception {
        TerminalEmulator emulator = emulatorWithWrappedRow();
        TerminalSession session = sessionWithEmulator(emulator);
        // Frame covering rows 0..2, as the parser worker publishes for this viewport.
        TerminalRenderFrame frame = new TerminalRenderFrame(emulator, 0, null, 0, -1, -1, -1, -1);

        String withFrame = controllerForRows01(session, frame).getSelectedText();
        String withoutFrame = controllerForRows01(session, null).getSelectedText();

        assertEquals("ABCDEFGHIJ", withFrame);
        assertEquals(withFrame, withoutFrame);
    }
}
