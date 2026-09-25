package com.termux.view;

import android.view.View;

/**
 * Policy for skipping clean rows on the Canvas backend.
 *
 * <p>Skipping a row means its pixels are never drawn this frame. That is only
 * correct when the draw target provably retains the previous draw's pixels, so
 * the retained content is still visible.</p>
 *
 * <p>What retains pixels, per Android Canvas/HWUI semantics:</p>
 * <ul>
 *   <li>Hardware-accelerated window (any layer type): a
 *   {@code LAYER_TYPE_NONE} view's {@code onDraw} records a display list that
 *   replaces the window content each draw; a {@code LAYER_TYPE_HARDWARE} view's
 *   layer is replayed from a re-recorded display list — on real devices the
 *   texture content does not preserve undrawn rows (disproven on device
 *   2026-09-25: {@code hwui_gpu} mode with skipped=80/81 produced flicker and
 *   missing content); a {@code LAYER_TYPE_SOFTWARE} layer must be "redrawn in
 *   its entirety" on every invalidation (per {@link View#setLayerType}
 *   contract). None of these retain.</li>
 *   <li>Software-rendered window + {@code LAYER_TYPE_NONE}: the canvas is the
 *   window's persistent surface bitmap — the classic dirty-region software
 *   rendering model — and pixels outside the redrawn region survive between
 *   draws. Retention holds.</li>
 *   <li>Software-rendered window + {@code LAYER_TYPE_SOFTWARE}: the draw target
 *   is the layer bitmap, which is redrawn in its entirety per invalidation —
 *   not the persistent window surface. No retention.</li>
 * </ul>
 *
 * <p>The gate therefore keys on the WINDOW-level acceleration flag
 * ({@link View#isHardwareAccelerated()} reflects the attached window, not the
 * onDraw canvas — a software layer inside a hardware window also yields a
 * software canvas, and conflating the two would re-open the lost-row bug for
 * the user-selectable {@code software} rendering mode).</p>
 */
final class CanvasRetentionPolicy {

    private CanvasRetentionPolicy() {
    }

    /**
     * Whether drawing into {@code layerType} inside a window with this
     * acceleration flag retains the previous draw's pixels in regions the
     * current draw does not touch. Only a software-rendered window with no view
     * layer (drawing directly into the persistent window surface) retains.
     */
    static boolean drawTargetRetainsPreviousPixels(boolean windowHardwareAccelerated,
                                                   int layerType) {
        return !windowHardwareAccelerated && layerType == View.LAYER_TYPE_NONE;
    }

    /**
     * Whether the Canvas backend may skip clean rows for this draw.
     *
     * @param windowHardwareAccelerated {@code view.isHardwareAccelerated()} —
     *                                   the attached window's acceleration state
     * @param layerType                  {@code view.getLayerType()}
     * @param fullRedraw                 {@code damage.fullRedraw}; every row
     *                                   must be redrawn when set
     * @param hasPreviousRenderedFrame   whether a previous frame was rendered
     *                                   into this same persistent draw target
     * @param reverseVideo               {@code frame.reverseVideo}; the
     *                                   renderer clears the whole canvas with
     *                                   the foreground color first, so no row
     *                                   can retain its previous pixels
     */
    static boolean shouldSkipCleanRows(boolean windowHardwareAccelerated,
                                       int layerType,
                                       boolean fullRedraw,
                                       boolean hasPreviousRenderedFrame,
                                       boolean reverseVideo) {
        return drawTargetRetainsPreviousPixels(windowHardwareAccelerated, layerType)
            && !fullRedraw
            && hasPreviousRenderedFrame
            && !reverseVideo;
    }
}
