package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;

public class TerminalRenderPolicyTest {

    @Test
    public void retainedLayerIsRequired() {
        assertFalse(TerminalRenderPolicy.shouldSkipCleanRows(
            View.LAYER_TYPE_NONE, false, true, false));
        assertTrue(TerminalRenderPolicy.shouldSkipCleanRows(
            View.LAYER_TYPE_HARDWARE, false, true, false));
        assertTrue(TerminalRenderPolicy.shouldSkipCleanRows(
            View.LAYER_TYPE_SOFTWARE, false, true, false));
    }

    @Test
    public void firstFrameCannotSkipRows() {
        assertFalse(TerminalRenderPolicy.shouldSkipCleanRows(
            View.LAYER_TYPE_HARDWARE, false, false, false));
    }

    @Test
    public void reverseVideoForcesFullRedraw() {
        assertFalse(TerminalRenderPolicy.shouldSkipCleanRows(
            View.LAYER_TYPE_HARDWARE, true, true, false));
    }

    @Test
    public void projectionOrGeometryChangeForcesFullRedraw() {
        assertFalse(TerminalRenderPolicy.shouldSkipCleanRows(
            View.LAYER_TYPE_HARDWARE, false, true, true));
    }
}
