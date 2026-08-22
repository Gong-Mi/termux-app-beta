package com.termux.view;

/** Shared pixel-to-terminal coordinate conversion for text selection gestures. */
final class TerminalSelectionCoordinates {
    private TerminalSelectionCoordinates() {}

    static int rowFromPixel(float y, int baselineOffset, int lineSpacing, int topRow) {
        if (lineSpacing <= 0) {
            throw new IllegalArgumentException("lineSpacing must be positive");
        }
        return (int) Math.floor((y - baselineOffset) / lineSpacing) + topRow;
    }
}
