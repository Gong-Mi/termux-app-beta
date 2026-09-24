package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class RenderGeometryTest {

    @Test
    public void geometryCarriesColumnsRowsAndPixels() {
        RenderGeometry geometry = new RenderGeometry(80, 24, 1080, 1920);
        assertEquals(80, geometry.columns);
        assertEquals(24, geometry.rows);
        assertEquals(1080, geometry.screenWidth);
        assertEquals(1920, geometry.screenHeight);
    }

    @Test
    public void equalityDependsOnAllFields() {
        RenderGeometry a = new RenderGeometry(80, 24, 1080, 1920);
        RenderGeometry b = new RenderGeometry(80, 24, 1080, 1920);
        RenderGeometry c = new RenderGeometry(81, 24, 1080, 1920);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertFalse(a.equals(null));
        assertTrue(a.equals(a));
    }

    @Test
    public void toStringIncludesDimensions() {
        RenderGeometry geometry = new RenderGeometry(80, 24, 1080, 1920);
        String text = geometry.toString();
        assertTrue(text.contains("80x24"));
        assertTrue(text.contains("1080x1920"));
    }
}
