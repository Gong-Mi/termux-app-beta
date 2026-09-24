package com.termux.view;

/**
 * Immutable render-target geometry known at attach time.
 *
 * <p>This is distinct from the per-frame projection (topRow, selection):
 * geometry describes the size of the rendering surface in cells and pixels,
 * shared across all frames rendered into one consumer attachment.</p>
 */
public final class RenderGeometry {

    public final int columns;
    public final int rows;
    public final int screenWidth;
    public final int screenHeight;

    public RenderGeometry(int columns, int rows, int screenWidth, int screenHeight) {
        this.columns = columns;
        this.rows = rows;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof RenderGeometry)) return false;
        RenderGeometry other = (RenderGeometry) object;
        return columns == other.columns
            && rows == other.rows
            && screenWidth == other.screenWidth
            && screenHeight == other.screenHeight;
    }

    @Override
    public int hashCode() {
        int result = columns;
        result = 31 * result + rows;
        result = 31 * result + screenWidth;
        result = 31 * result + screenHeight;
        return result;
    }

    @Override
    public String toString() {
        return "RenderGeometry{" + columns + "x" + rows + " @ " + screenWidth + "x" + screenHeight + '}';
    }
}
