package com.termux.view;

/**
 * Immutable conversion from the selection controller protocol to render-frame coordinates.
 *
 * <p>The controller exports selectors as {@code [y1, y2, x1, x2]}, while
 * {@link TerminalRenderFrame} stores them as {@code x1, y1, x2, y2}.
 */
final class TerminalSelectionRange {
    final int x1;
    final int y1;
    final int x2;
    final int y2;

    private TerminalSelectionRange(int x1, int y1, int x2, int y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    static TerminalSelectionRange fromSelectors(int[] selectors) {
        if (selectors == null || selectors.length != 4) {
            throw new IllegalArgumentException("selectors must contain y1, y2, x1, x2");
        }
        return new TerminalSelectionRange(
            selectors[2],
            selectors[0],
            selectors[3],
            selectors[1]);
    }
}
