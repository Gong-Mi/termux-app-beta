package com.termux.app.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link RootViewBottomMarginPolicy}.
 *
 * Regression context: TermuxActivityRootView's margin state machine kept
 * cross-session state (lastMarginBottom memo, wall-clock throttles, an
 * onMeasure prefetch) to converge on a bottom margin that keeps the terminal
 * above the IME. The cold-start first keyboard show ran with an empty memo
 * and a coalesced-traversal window, so the system could rest in the wrong
 * fixed point (margin 0 with the keyboard open, terminal drawn under the IME)
 * until a later show re-converged. The policy replaces that state with a
 * single pure function whose fixed point is correct by construction:
 *
 *   M' = max(0, viewBottom + currentMargin - visibleWindowBottom)
 *
 * viewBottom is the laid-out bottom in window coordinates, i.e. it already
 * includes the effect of the current margin; adding currentMargin recovers
 * where the content bottom would sit without the margin, so M' is the margin
 * that aligns the content bottom with the visible window bottom.
 */
public class RootViewBottomMarginPolicyTest {

    @Test
    public void coveredPartOfViewBecomesTheMargin() {
        // Content bottom 300px below the visible window bottom, no margin
        // currently applied -> margin 300.
        assertEquals(300, RootViewBottomMarginPolicy.targetMarginBottom(1300, 1000, 0));
    }

    @Test
    public void appliedMarginIsNotDoubleCountedAtTheFixedPoint() {
        // Margin 300 already applied: the view bottom is lifted to the
        // visible bottom. Re-evaluation must return 300, not 0 or 600.
        assertEquals(300, RootViewBottomMarginPolicy.targetMarginBottom(1000, 1000, 300));
    }

    @Test
    public void frameworkResizeNeedsNoMargin() {
        // adjustResize active: view bottom already sits at the visible bottom
        // with margin 0 -> margin 0.
        assertEquals(0, RootViewBottomMarginPolicy.targetMarginBottom(1000, 1000, 0));
    }

    @Test
    public void viewAboveWindowBottomYieldsZeroMargin() {
        // Extra room below the view (freeform/split-screen) never produces a
        // negative margin.
        assertEquals(0, RootViewBottomMarginPolicy.targetMarginBottom(900, 1000, 0));
    }

    @Test
    public void staleLargerMarginConvergesDownWhenKeyboardShrinks() {
        // Keyboard shrank from 300 to 100 covered px. With margin 300 applied
        // the view bottom sits at rawBottom(1100) - 300 = 800.
        assertEquals(100, RootViewBottomMarginPolicy.targetMarginBottom(800, 1000, 300));
    }

    @Test
    public void marginReleasedWhenKeyboardCloses() {
        // Keyboard closed: visible bottom back at the content bottom; the old
        // margin must be released, not left applied.
        assertEquals(0, RootViewBottomMarginPolicy.targetMarginBottom(700, 1000, 300));
    }
}
