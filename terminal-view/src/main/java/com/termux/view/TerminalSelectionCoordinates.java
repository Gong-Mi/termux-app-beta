package com.termux.view;

/**
 * Shared pixel-to-terminal coordinate conversion for text selection gestures.
 *
 * <p>Single-source-of-truth invariant (issue #39): gesture anchors, handle geometry and the
 * ActionMode rect must all be expressed in the same coordinate space as the content the renderer
 * actually drew. The scroll <em>target</em> ({@code mTopRow}) can differ from the viewport of the
 * last published/rendered frame while the parser worker is catching up; using the target for
 * gesture mapping makes the anchor refer to text that is not under the finger and the handle float
 * over a different highlight region. All selection-side conversions therefore resolve against the
 * last rendered frame's viewport and fall back to the scroll target only before the first frame.
 */
public final class TerminalSelectionCoordinates {
    private TerminalSelectionCoordinates() {}

    public static int rowFromPixel(float y, int baselineOffset, int lineSpacing, int topRow) {
        if (lineSpacing <= 0) {
            throw new IllegalArgumentException("lineSpacing must be positive");
        }
        return (int) Math.floor((y - baselineOffset) / lineSpacing) + topRow;
    }

    /**
     * The external row of the content at pixel {@code y}, aligned to the last <em>rendered</em>
     * frame's viewport (what the user actually sees). The scroll target must never be used once a
     * frame exists, otherwise the anchor diverges from the displayed text by the viewport gap.
     */
    public static int rowOfRenderedContent(float y, int baselineOffset, int lineSpacing,
                                    int scrollTargetTopRow, int renderedTopRow, boolean frameRendered) {
        return rowFromPixel(y, baselineOffset, lineSpacing,
            frameRendered ? renderedTopRow : scrollTargetTopRow);
    }

    /**
     * View-local pixel of the top of external row {@code externalRow} when the rendered frame's
     * viewport is displayed. Symmetric with {@link #rowOfRenderedContent}: both resolve against the
     * rendered viewport so handles sit on the rows the renderer drew.
     */
    public static int pixelOfRenderedRow(int externalRow, int lineSpacing,
                                  int scrollTargetTopRow, int renderedTopRow, boolean frameRendered) {
        if (lineSpacing <= 0) {
            throw new IllegalArgumentException("lineSpacing must be positive");
        }
        int topRow = frameRendered ? renderedTopRow : scrollTargetTopRow;
        return Math.round((externalRow - topRow) * lineSpacing);
    }

    /**
     * Clamp a selection anchor to [firstCaptured, endCaptured) so text extraction never reads a row
     * outside the captured snapshot window. The transcript scroll limit (-activeTranscriptRows) is
     * still respected when it is wider than the captured window; the captured window wins when the
     * viewport has not yet scrolled to the transcript top, so a drag cannot silently select blank
     * history while the worker frame is lagging behind.
     */
    public static int clampSelectionRow(int row, int activeTranscriptRows,
                                 int capturedFirstExternalRow, int capturedEndExternalRow) {
        if (capturedEndExternalRow <= capturedFirstExternalRow) {
            return capturedFirstExternalRow;
        }
        int lower = Math.max(-activeTranscriptRows, capturedFirstExternalRow);
        int upper = capturedEndExternalRow - 1;
        return Math.max(lower, Math.min(row, upper));
    }
}