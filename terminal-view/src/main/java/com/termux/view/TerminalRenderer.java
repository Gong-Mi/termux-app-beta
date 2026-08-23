package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRenderRow;
import com.termux.terminal.TerminalScreenSnapshot;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];
    /** Per-render step counters for diagnostics (no behavioral effect). */
    private final TerminalRenderStepMetrics mRenderSteps = new TerminalRenderStepMetrics();

    /**
     * Lazily populated BMP (U+0000..U+FFFF) single-code-point advances.
     *
     * <p>Same invariant as {@link #asciiMeasures}: the advance of a single code point
     * does not depend on the paint style left over by the previous draw, only on the
     * typeface/size fixed at construction. TerminalView redraws the whole screen on
     * every invalidation, re-measuring the same non-ASCII code points (e.g. the
     * U+2580 upper-block used by half-block media renderers) over and over; the
     * per-frame measureText/setFlags call on those is the main-thread hot path.
     * 0.0f marks an uncached entry. Zero-width code points (combining marks) are
     * rare and simply re-measure; they are consumed separately by WcWidth.
     */
    private final float[] bmpMeasures = new float[0x10000];
    /** Number of rows skipped by the last render (diagnostics/observability only). */
    private int lastSkippedRowCount;

    /** Rows skipped by the most recent render; 0 when skipCleanRows was not enabled. */
    public int getLastSkippedRowCount() {
        return lastSkippedRowCount;
    }

    /** Drain per-render step counters accumulated since the last call. */
    public TerminalRenderStepMetrics.Snapshot getAndResetRenderStepDelta() {
        return mRenderSteps.getAndResetDelta();
    }

    /**
     * Measure a single code point, caching the result for the life of this renderer.
     * BMP code points hit {@link #bmpMeasures}; supplementary code points
     * (surrogate pairs) are measured directly since they cannot alias the table.
     */
    private float measureCodePoint(int codePoint, char[] line, int charIndex, int charsForCodePoint) {
        mRenderSteps.recordGlyphMeasureCall();
        if (codePoint < bmpMeasures.length) {
            float cached = bmpMeasures[codePoint];
            if (cached != 0f) return cached;
            float measured = mTextPaint.measureText(line, charIndex, 1);
            bmpMeasures[codePoint] = measured;
            return measured;
        }
        return mTextPaint.measureText(line, charIndex, charsForCodePoint);
    }

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalRenderFrame frame, Canvas canvas) {
        render(frame, canvas, false, null);
    }

    /**
     * Render the terminal to a canvas.
     *
     * @param skipCleanRows when true, rows whose content provably did not change
     *                      since the previous rendering of the same viewport are
     *                      skipped. The caller must only pass true when the view is
     *                      layered (hardware/software layer) so that skipped rows
     *                      retain their previous pixels, when
     *                      {@link TerminalRenderFrame#needsFullRedraw(TerminalRenderFrame)}
     *                      is false for the previous frame, and when that previous
     *                      frame is supplied as {@code previousRenderedFrame}.
     */
    public final void render(TerminalRenderFrame frame, Canvas canvas, boolean skipCleanRows, TerminalRenderFrame previousRenderedFrame) {
        final boolean reverseVideo = frame.reverseVideo;
        final int endRow = frame.endRow;
        final int columns = frame.columns;
        final int cursorCol = frame.cursorCol;
        final int cursorRow = frame.cursorRow;
        final boolean cursorVisible = frame.cursorVisible;
        final TerminalScreenSnapshot screen = frame.screen;
        final int[] palette = frame.paletteForRenderer();
        final int cursorShape = frame.cursorStyle;
        final int selectionX1 = frame.selectionX1;
        final int selectionY1 = frame.selectionY1;
        final int selectionX2 = frame.selectionX2;
        final int selectionY2 = frame.selectionY2;
        final int topRow = frame.topRow;

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        int skippedRows = 0;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            if (skipCleanRows
                    && frame.rowUnchangedFrom(previousRenderedFrame, row)
                    && !(cursorVisible && row == cursorRow)
                    && !needsRedrawForProjection(previousRenderedFrame, row,
                        selectionY1, selectionY2)) {
                // The layered canvas keeps the pixels produced by the previous frame
                // for this row; nothing changed in the buffer here, so skip measuring
                // and drawing it entirely. Cursor and selection rows are view
                // projections, not buffer content, so they always redraw - including
                // rows that held the cursor/selection in the previous frame.
                skippedRows++;
                mRenderSteps.recordSkippedRow();
                continue;
            }
            mRenderSteps.recordVisitedRow();

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : columns;
            }

            TerminalRenderRow lineObject = screen.rowAtExternal(row);
            // TerminalRenderRow is immutable; avoid copying the complete row on every draw.
            final char[] line = lineObject.textForRenderer();
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                mRenderSteps.recordVisitedCell();
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                mRenderSteps.recordWcWidthCall();
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : measureCodePoint(codePoint, line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }
        lastSkippedRowCount = skippedRows;
    }

    /**
     * Whether {@code row} intersects a view projection that is not part of the buffer
     * content: the selection rectangle of the current or previous frame, or the cursor
     * row of the previous frame (the current frame's cursor row is checked by the
     * caller). Such rows must redraw even when their buffer content is unchanged,
     * because the projection pixels are not stored in the snapshot.
     */
    private static boolean needsRedrawForProjection(TerminalRenderFrame previousRenderedFrame, int row,
                                                    int selectionY1, int selectionY2) {
        if (row >= selectionY1 && row <= selectionY2) return true;
        if (previousRenderedFrame == null) return true;
        if (previousRenderedFrame.cursorVisible && row == previousRenderedFrame.cursorRow) return true;
        return row >= previousRenderedFrame.selectionY1 && row <= previousRenderedFrame.selectionY2;
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        mRenderSteps.recordDrawTextRunCall();
        long t0 = System.nanoTime();
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        // Phase 1: paint setup (foreColor/backColor/effect → now)
        long t1 = System.nanoTime();
        mRenderSteps.recordPaintSetupNanos(t1 - t0);

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
            mRenderSteps.recordDrawRectCall();
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
            mRenderSteps.recordDrawRectCall();
        }

        // Phase 2: drawRect calls complete
        long t2 = System.nanoTime();
        mRenderSteps.recordDrawRectNanos(t2 - t1);

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
        }

        if (savedMatrix) canvas.restore();
        mRenderSteps.recordDrawTextNanos(System.nanoTime() - t2);
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
