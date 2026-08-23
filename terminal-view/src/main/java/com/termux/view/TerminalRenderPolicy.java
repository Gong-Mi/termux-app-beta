package com.termux.view;

import android.view.View;

/**
 * Render policy for the retained-pixel dirty-row experiment.
 *
 * <p>This class contains only the safety gate. It does not select a layer type
 * and it does not change the default rendering mode. A caller may skip clean
 * rows only when the canvas is retaining pixels and no full redraw condition is
 * active.</p>
 */
final class TerminalRenderPolicy {

    private TerminalRenderPolicy() {
    }

    static boolean isRetainedPixelLayer(int layerType) {
        return layerType == View.LAYER_TYPE_HARDWARE
            || layerType == View.LAYER_TYPE_SOFTWARE;
    }

    static boolean shouldSkipCleanRows(int layerType, boolean reverseVideo,
                                       boolean hasPreviousRenderedFrame,
                                       boolean needsFullRedraw) {
        return isRetainedPixelLayer(layerType)
            && !reverseVideo
            && hasPreviousRenderedFrame
            && !needsFullRedraw;
    }
}
