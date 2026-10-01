package com.termux.app.terminal;

import org.junit.Test;

import static com.termux.app.terminal.SoftKeyboardTogglePolicy.Action.DISABLE_AND_PERSIST;
import static com.termux.app.terminal.SoftKeyboardTogglePolicy.Action.ENABLE_AND_SHOW;
import static com.termux.app.terminal.SoftKeyboardTogglePolicy.Action.HIDE;
import static com.termux.app.terminal.SoftKeyboardTogglePolicy.Action.SHOW;
import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link SoftKeyboardTogglePolicy}.
 *
 * Regression context: the toggle decision previously inferred IME visibility
 * from the FLAG_ALT_FOCUSABLE_IM window flag (a "keyboard disabled" proxy),
 * so it misclassified "flag cleared but IME actually dismissed" and flipped
 * the user's enabled preference, or took the wrong branch in
 * hide-on-startup mode. The policy now takes the measured visibility as
 * input; these tests pin the decision table.
 */
public class SoftKeyboardTogglePolicyTest {

    @Test
    public void enableDisableModeDisablesOnlyWhenKeyboardActuallyVisible() {
        assertEquals(DISABLE_AND_PERSIST, SoftKeyboardTogglePolicy.onToggleEnableDisableMode(true));
    }

    @Test
    public void enableDisableModeShowsWhenKeyboardActuallyHidden() {
        // Covers the desync case: IME dismissed via back gesture / never shown.
        assertEquals(ENABLE_AND_SHOW, SoftKeyboardTogglePolicy.onToggleEnableDisableMode(false));
    }

    @Test
    public void showHideModeHidesOnlyWhenKeyboardActuallyVisible() {
        assertEquals(HIDE, SoftKeyboardTogglePolicy.onToggleShowHideMode(true));
    }

    @Test
    public void showHideModeShowsWhenKeyboardActuallyHidden() {
        // Replaces the blind InputMethodManager.toggleSoftInput() round trip.
        assertEquals(SHOW, SoftKeyboardTogglePolicy.onToggleShowHideMode(false));
    }
}
