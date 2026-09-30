package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;

/**
 * Contract for the clean-row skip gate. Skipping a row is only correct when the
 * draw target provably retains the previous draw's pixels; a skipped row is
 * never redrawn, so any target that starts each draw effectively empty loses
 * that row's content entirely (observed on device as flicker + missing rows
 * under {@code hwui_gpu}).
 *
 * <p>Retention matrix (window acceleration × view layer type):</p>
 * <ul>
 *   <li>Hardware window, {@code LAYER_TYPE_NONE}: display-list re-recording;
 *   no retention.</li>
 *   <li>Hardware window, {@code LAYER_TYPE_HARDWARE}: full-view layer replayed
 *   from a re-recorded display list; undrawn rows are not preserved (disproven
 *   on device 2026-09-25: skipped=80/81 → flicker + missing content).</li>
 *   <li>Hardware window, {@code LAYER_TYPE_SOFTWARE}: the layer bitmap is
 *   redrawn in its entirety per invalidation; no retention.</li>
 *   <li>Software window, {@code LAYER_TYPE_NONE}: the persistent window
 *   surface retains prior pixels outside the drawn region — retention holds.</li>
 *   <li>Software window, {@code LAYER_TYPE_SOFTWARE}: the draw target is the
 *   layer bitmap (redrawn entirely per invalidation), not the window surface;
 *   no retention.</li>
 * </ul>
 *
 * <p>The gate keys on the WINDOW-level flag ({@code view.isHardwareAccelerated()}),
 * not {@code canvas.isHardwareAccelerated()}: a software layer inside a
 * hardware window yields a software onDraw canvas yet still loses skipped rows.</p>
 */
public class CanvasRetentionPolicyTest {

    // ---- retention matrix ----------------------------------------------

    @Test
    public void hardwareWindowNeverRetains() {
        assertFalse(CanvasRetentionPolicy.drawTargetRetainsPreviousPixels(
            true, View.LAYER_TYPE_NONE));
        assertFalse(CanvasRetentionPolicy.drawTargetRetainsPreviousPixels(
            true, View.LAYER_TYPE_HARDWARE));
        assertFalse(CanvasRetentionPolicy.drawTargetRetainsPreviousPixels(
            true, View.LAYER_TYPE_SOFTWARE));
    }

    @Test
    public void softwareWindowRetainsOnlyWithoutLayer() {
        // -gpu off / software window drawing directly into its persistent
        // surface: the only retained-pixel configuration.
        assertTrue(CanvasRetentionPolicy.drawTargetRetainsPreviousPixels(
            false, View.LAYER_TYPE_NONE));
        // A software layer diverts drawing to its own bitmap, redrawn in full
        // per invalidation — not retained.
        assertFalse(CanvasRetentionPolicy.drawTargetRetainsPreviousPixels(
            false, View.LAYER_TYPE_SOFTWARE));
        assertFalse(CanvasRetentionPolicy.drawTargetRetainsPreviousPixels(
            false, View.LAYER_TYPE_HARDWARE));
    }

    // ---- full gate ------------------------------------------------------

    @Test
    public void fullRedrawDisablesSkip() {
        // damage.fullRedraw (geometry/palette/reverse-video change): every row
        // must be redrawn regardless of canvas persistence.
        assertFalse(CanvasRetentionPolicy.shouldSkipCleanRows(
            false, View.LAYER_TYPE_NONE, true, true, false));
    }

    @Test
    public void missingPreviousFrameDisablesSkip() {
        // No previous rendered frame -> rowUnchangedFrom has no baseline.
        assertFalse(CanvasRetentionPolicy.shouldSkipCleanRows(
            false, View.LAYER_TYPE_NONE, false, false, false));
    }

    @Test
    public void reverseVideoDisablesSkip() {
        // Steady-state reverse video: the renderer clears the whole canvas with
        // the foreground color (PorterDuff.SRC) before drawing rows, so skipped
        // rows would show the clear color instead of their previous pixels.
        // RenderDamage.fullRedraw only covers the reverseVideo TRANSITION; the
        // steady state needs the frame flag.
        assertFalse(CanvasRetentionPolicy.shouldSkipCleanRows(
            false, View.LAYER_TYPE_NONE, false, true, true));
    }

    @Test
    public void retainedTargetWithoutFullRedrawCanSkip() {
        assertTrue(CanvasRetentionPolicy.shouldSkipCleanRows(
            false, View.LAYER_TYPE_NONE, false, true, false));
    }

    // ---- the regression this slice exists for ---------------------------

    @Test
    public void hwuiGpuModeNeverSkips() {
        // hwui_gpu = hardware window + LAYER_TYPE_HARDWARE. This exact cell
        // lost 80/81 rows per frame on device (2026-09-25); it must never skip.
        assertFalse(CanvasRetentionPolicy.shouldSkipCleanRows(
            true, View.LAYER_TYPE_HARDWARE, false, true, false));
    }

    @Test
    public void softwareModeInsideHardwareWindowNeverSkips() {
        // software rendering mode = LAYER_TYPE_SOFTWARE. Inside the default
        // hardware-accelerated window the layer bitmap is rebuilt per
        // invalidation; skipping rows there is the same lost-pixel bug.
        assertFalse(CanvasRetentionPolicy.shouldSkipCleanRows(
            true, View.LAYER_TYPE_SOFTWARE, false, true, false));
    }
}
