package com.termux.app.terminal;

/**
 * Pure decision logic for the soft keyboard toggle behaviour, extracted from
 * {@link TermuxTerminalViewClient#onToggleSoftKeyboardRequest()} so the state
 * machine can be unit tested without Android framework dependencies.
 *
 * The policy takes the <em>measured</em> soft keyboard visibility as input
 * instead of inferring it from the {@code FLAG_ALT_FOCUSABLE_IM} window flag.
 * The flag is only a "keyboard disabled" proxy and desyncs from real IME
 * visibility, e.g. when the IME was dismissed with the back gesture, was
 * hidden on startup, or never shown. Feeding the proxy in as the visibility
 * argument below API 23 preserves the legacy behaviour there.
 */
public final class SoftKeyboardTogglePolicy {

    public enum Action {
        /** Keyboard is visible: disable it and persist the disabled preference. */
        DISABLE_AND_PERSIST,
        /** Keyboard is hidden: enable it (clearing disable flags) and show it. */
        ENABLE_AND_SHOW,
        /** Keyboard is disabled by user preference: keep it disabled. */
        MAINTAIN_DISABLED,
        SHOW,
        HIDE
    }

    private SoftKeyboardTogglePolicy() {
    }

    /**
     * Decision for the "enable/disable" toggle mode
     * ({@code soft_keyboard_toggle_behaviour=enable/disable}).
     *
     * @param softKeyboardVisible Actual IME visibility, or the
     * {@code FLAG_ALT_FOCUSABLE_IM}-derived proxy when real visibility is
     * unavailable (API &lt; 23).
     */
    public static Action onToggleEnableDisableMode(boolean softKeyboardVisible) {
        return softKeyboardVisible ? Action.DISABLE_AND_PERSIST : Action.ENABLE_AND_SHOW;
    }

    /**
     * Decision for the "show/hide" toggle mode when the keyboard is enabled in
     * preferences. Replaces the blind
     * {@code InputMethodManager.toggleSoftInput()} round trip, which can flip
     * opposite to the real IME state.
     *
     * @param softKeyboardVisible Actual IME visibility, or the proxy below API 23.
     */
    public static Action onToggleShowHideMode(boolean softKeyboardVisible) {
        return softKeyboardVisible ? Action.HIDE : Action.SHOW;
    }
}
