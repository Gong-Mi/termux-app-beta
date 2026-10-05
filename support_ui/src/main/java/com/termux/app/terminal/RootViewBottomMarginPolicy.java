package com.termux.app.terminal;

/**
 * Pure fixed-point rule for the bottom margin that keeps the terminal content
 * above the soft keyboard, extracted from {@link TermuxActivityRootView} so
 * the convergence behaviour can be unit tested without Android dependencies.
 *
 * The previous implementation converged via cross-session state (a memoized
 * keyboard height, wall-clock throttles, an onMeasure prefetch), which left
 * the cold-start first keyboard show resting in a wrong fixed point. This
 * rule is stateless: re-evaluating it at any time from consistent inputs
 * converges in one step and is idempotent at the fixed point.
 *
 * Semantics of the inputs:
 * <ul>
 * <li>{@code viewBottom} — the root view's laid-out bottom edge in window
 * coordinates, i.e. already including the effect of the currently applied
 * bottom margin.</li>
 * <li>{@code visibleWindowBottom} — the bottom of the window region not
 * covered by system windows (from
 * {@code getWindowVisibleDisplayFrame}); already excludes the IME height the
 * framework accounts for.</li>
 * <li>{@code currentMarginBottom} — the margin currently applied to the root
 * view.</li>
 * </ul>
 *
 * The content bottom <em>without</em> the margin's lift is
 * {@code viewBottom + currentMarginBottom}, so the margin that aligns it with
 * the visible window bottom is their difference, clamped at zero.
 */
public final class RootViewBottomMarginPolicy {

    private RootViewBottomMarginPolicy() {
    }

    /**
     * @return The bottom margin the root view should have; re-evaluation with
     * the margin applied to the layout (and therefore reflected in
     * {@code viewBottom}) returns the same value.
     */
    public static int targetMarginBottom(int viewBottom, int visibleWindowBottom, int currentMarginBottom) {
        return Math.max(0, viewBottom + currentMarginBottom - visibleWindowBottom);
    }
}
