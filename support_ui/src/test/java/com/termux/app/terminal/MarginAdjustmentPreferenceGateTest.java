package com.termux.app.terminal;

import static org.junit.Assert.fail;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import com.termux.app.TermuxActivity;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * Audit L3-1: the "Terminal Margin Adjustment" preference is the master gate for every
 * bottom-margin update. Upstream only registers the OnGlobalLayoutListener when the
 * preference is enabled, but the fork's WindowInsetsListener path calls
 * updateBottomMargin() unconditionally. With the preference off, any insets change
 * (keyboard show/hide, system bars) still wrote a bottom margin, bypassing the setting.
 *
 * Gate contract pinned here: updateBottomMargin() must return before touching the view
 * state when the preference is disabled, and must still proceed when it is enabled.
 * Android surface is bypassed with Unsafe allocation + field injection (same pattern as
 * TextSelectionLifecycleHideTest in terminal-view); the gate logic under test is
 * production code (TermuxActivityRootView.updateBottomMargin + the real preference
 * chain through TermuxAppSharedPreferences, allocated via reflection because
 * termux-shared is compileOnly for this module's unit tests).
 */
public class MarginAdjustmentPreferenceGateTest {

    private static final Object ALLOCATOR;
    private static final Method ALLOCATE;
    private static final String PREFS_CLASS = "com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences";

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

    /** Sentinel proving the update path reached the first view-state access (getLayoutParams). */
    private static final class ReachedViewUpdatePath extends RuntimeException {
    }

    /** A root view whose first view-state access is observable: it throws the sentinel. */
    private static final class ProbeRootView extends TermuxActivityRootView {
        private ProbeRootView() {
            super(null, null); // never executed: instances are allocated with Unsafe
        }

        @Override
        public ViewGroup.LayoutParams getLayoutParams() {
            throw new ReachedViewUpdatePath();
        }
    }

    /** Minimal SharedPreferences carrying only the margin preference value. */
    private static final class FakeSharedPreferences implements SharedPreferences {
        private final boolean marginAdjustmentEnabled;

        private FakeSharedPreferences(boolean marginAdjustmentEnabled) {
            this.marginAdjustmentEnabled = marginAdjustmentEnabled;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            if ("terminal_margin_adjustment".equals(key)) return marginAdjustmentEnabled;
            return defValue;
        }

        @Override public Map<String, ?> getAll() { return null; }
        @Override public String getString(String key, String defValue) { return defValue; }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean contains(String key) { return false; }
        @Override public Editor edit() { return null; }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }

    /**
     * A visible activity whose preference chain answers marginAdjustmentEnabled, with a
     * non-null bottom space view so the update path, when reached, has real work to do.
     */
    private static ProbeRootView rootViewWithPreference(boolean marginAdjustmentEnabled) throws Exception {
        Object prefs = allocate(Class.forName(PREFS_CLASS));
        set(prefs, "mSharedPreferences", new FakeSharedPreferences(marginAdjustmentEnabled));

        TermuxActivity activity = (TermuxActivity) allocate(TermuxActivity.class);
        set(activity, "mIsVisible", true);
        set(activity, "mPreferences", prefs);
        set(activity, "mTermuxActivityBottomSpaceView", allocate(View.class));

        ProbeRootView root = (ProbeRootView) allocate(ProbeRootView.class);
        root.mActivity = activity;
        return root;
    }

    @Test
    public void marginUpdatesMustNotRunWhenPreferenceDisabled() throws Exception {
        ProbeRootView root = rootViewWithPreference(false);
        // Pre-fix this threw ReachedViewUpdatePath: the insets trigger bypassed the
        // preference and wrote margins with the setting off (audit L3-1).
        root.updateBottomMargin();
    }

    @Test
    public void marginUpdatesStillRunWhenPreferenceEnabled() throws Exception {
        ProbeRootView root = rootViewWithPreference(true);
        try {
            root.updateBottomMargin();
            fail("expected the update path to proceed when the preference is enabled");
        } catch (ReachedViewUpdatePath expected) {
            // Reaching the view-update path is exactly what an enabled preference must do.
        }
    }
}
