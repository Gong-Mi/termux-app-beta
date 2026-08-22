package com.termux.view;

import android.view.ActionMode;

import java.util.Locale;

/**
 * Selects the framework action mode variant for terminal text selection.
 *
 * <p>AOSP's floating action mode accepts any originating {@code View}. Some
 * Xiaomi builds replace it with a private implementation that assumes the
 * originating view is a {@code TextView}; TerminalView intentionally is not a
 * TextView. Use the primary mode on those builds so selection remains usable.
 */
public final class TerminalActionModePolicy {
    private TerminalActionModePolicy() {}

    public static int typeFor(String manufacturer, String brand) {
        if (isXiaomiFamily(manufacturer) || isXiaomiFamily(brand)) {
            return ActionMode.TYPE_PRIMARY;
        }
        return ActionMode.TYPE_FLOATING;
    }

    private static boolean isXiaomiFamily(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("xiaomi")
            || normalized.contains("redmi")
            || normalized.equals("poco")
            || normalized.startsWith("poco ");
    }
}
