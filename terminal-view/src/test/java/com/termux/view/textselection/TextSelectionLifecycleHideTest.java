package com.termux.view.textselection;

import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Controller lifecycle logic only: handles have no Android PopupWindow in this JVM fixture. */
public class TextSelectionLifecycleHideTest {
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
    private static Object get(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }
    /** Only Android View invalidation is unavailable on the host, not handle-hide logic. */
    private static final class WindowlessHandle extends TextSelectionHandleView {
        int invalidations;
        private WindowlessHandle() { super(null, null, LEFT); }
        @Override public void invalidate() { invalidations++; }
    }
    private static TextSelectionCursorController selectingNow() throws Exception {
        // Bypass only Android resource/window construction, not the production hide implementation.
        TextSelectionCursorController c = (TextSelectionCursorController)
            allocate(TextSelectionCursorController.class);
        TextSelectionHandleView left = (TextSelectionHandleView) allocate(WindowlessHandle.class);
        TextSelectionHandleView right = (TextSelectionHandleView) allocate(WindowlessHandle.class);
        set(left, "mIsDragging", true);
        set(right, "mIsDragging", true);
        set(c, "mStartHandle", left);
        set(c, "mEndHandle", right);
        set(c, "mIsSelectingText", true);
        // Also expose that wall-clock rollback must not veto a lifecycle teardown.
        set(c, "mShowStartTime", System.currentTimeMillis() + 60_000);
        for (String name : new String[] {"mSelX1", "mSelX2", "mSelY1", "mSelY2"}) set(c, name, 3);
        return c;
    }
    private static boolean lifecycleHide(TextSelectionCursorController c) throws Exception {
        Method entry;
        try {
            entry = TextSelectionCursorController.class.getMethod("hideForSessionChange");
        } catch (NoSuchMethodException preFix) {
            // This is the production entry the current attachSession path uses before the fix.
            entry = TextSelectionCursorController.class.getMethod("hide");
        }
        return (Boolean) entry.invoke(c);
    }

    @Test public void sessionSwitchMustEndFreshSelectionDespiteGestureDebounce() throws Exception {
        TextSelectionCursorController c = selectingNow();
        assertTrue("lifecycle teardown may not refuse a fresh selection", lifecycleHide(c));
        assertFalse(c.isActive());
        for (String name : new String[] {"mSelX1", "mSelX2", "mSelY1", "mSelY2"}) assertEquals(-1, get(c, name));
        assertEquals(false, get(get(c, "mStartHandle"), "mIsDragging"));
        assertEquals(false, get(get(c, "mEndHandle"), "mIsDragging"));
    }
    @Test public void ordinaryGestureHideRetainsItsDebounce() throws Exception {
        TextSelectionCursorController c = selectingNow();
        assertFalse(c.hide());
        assertTrue(c.isActive());
        assertEquals(3, get(c, "mSelX1"));
    }
    @Test public void repeatedLifecycleHideHasNoSecondStateTransition() throws Exception {
        TextSelectionCursorController c = selectingNow();
        assertTrue(lifecycleHide(c));
        assertFalse(lifecycleHide(c));
        assertFalse(c.isActive());
    }
}
