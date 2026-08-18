package com.termux.terminal;

import java.util.Arrays;

/**
 * Immutable snapshot of the terminal model at a specific screen revision.
 *
 * <p>This object is produced by the parser worker thread and consumed by the
 * render thread. It decouples model mutation from rendering: the worker owns
 * the {@link TerminalEmulator}, captures a snapshot, and hands it off. The
 * render side (typically in the terminal-view module) can then build a
 * {@code TerminalRenderFrame} with its own viewport/selection without touching
 * the emulator.</p>
 */
public final class TerminalModelFrame {

    /** Viewport top row used when this snapshot was captured. */
    public final int topRow;
    /** One past the last visible row: topRow + rows. */
    public final int endRow;
    /** Number of visible rows. */
    public final int rows;
    /** Number of columns. */
    public final int columns;

    /** Cursor state captured at snapshot time. */
    public final int cursorCol;
    public final int cursorRow;
    public final int cursorStyle;
    public final boolean cursorVisible;

    /** Reverse-video flag captured at snapshot time. */
    public final boolean reverseVideo;

    /** Mouse tracking state captured at snapshot time. */
    public final boolean mouseTrackingActive;

    /** Alternate buffer state captured at snapshot time. */
    public final boolean alternateBufferActive;

    /** Auto-scroll disabled state captured at snapshot time. */
    public final boolean autoScrollDisabled;

    /** Key passthrough state captured at snapshot time (unused, kept for external API parity). */
    public final boolean keyPassthroughEnabled;

    /** Active transcript rows captured at snapshot time. */
    public final int activeTranscriptRows;

    /** Current color palette at snapshot time. */
    private final int[] palette;

    /** Screen contents for the captured viewport. */
    public final TerminalScreenSnapshot screen;

    /** Parser/model batch revision observed when this frame was collected. */
    public final long screenRevision;

    /**
     * Dirty-row journal snapshot: rows modified since the previous frame was
     * captured. Used for diagnostics and future dirty-region rendering.
     */
    private final long[] dirtyRowBits;
    public final int dirtyMutationCount;

    public TerminalModelFrame(TerminalEmulator emulator, int topRow, long[] dirtyRowBits, int dirtyMutationCount) {
        this.topRow = topRow;
        this.rows = emulator.mRows;
        this.columns = emulator.mColumns;
        this.endRow = topRow + rows;
        this.cursorCol = emulator.getCursorCol();
        this.cursorRow = emulator.getCursorRow();
        this.cursorStyle = emulator.getCursorStyle();
        this.cursorVisible = emulator.shouldCursorBeVisible();
        this.reverseVideo = emulator.isReverseVideo();
        this.mouseTrackingActive = emulator.isMouseTrackingActive();
        this.alternateBufferActive = emulator.isAlternateBufferActive();
        this.autoScrollDisabled = emulator.isAutoScrollDisabled();
        this.keyPassthroughEnabled = false;
        this.activeTranscriptRows = emulator.getScreen().getActiveTranscriptRows();
        this.palette = Arrays.copyOf(emulator.mColors.mCurrentColors, emulator.mColors.mCurrentColors.length);
        this.screen = TerminalScreenSnapshot.capture(emulator.getScreen(), this.topRow, this.endRow, this.columns);
        this.screenRevision = emulator.getScreenRevision();
        this.dirtyRowBits = dirtyRowBits == null ? null : Arrays.copyOf(dirtyRowBits, dirtyRowBits.length);
        this.dirtyMutationCount = dirtyMutationCount;
    }

    public int[] copyPalette() {
        return Arrays.copyOf(palette, palette.length);
    }

    public long[] copyDirtyRowBits() {
        return dirtyRowBits == null ? null : Arrays.copyOf(dirtyRowBits, dirtyRowBits.length);
    }
}
