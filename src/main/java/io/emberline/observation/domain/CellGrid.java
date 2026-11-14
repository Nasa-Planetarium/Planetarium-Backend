package io.emberline.observation.domain;

/**
 * The 0.1° grid of the release: {@code cell_id = (floor(lat×10)+900)×3600 + (floor(lon×10)+1800)}, center = corner
 * + 0.05 (INSPECT: 0 mismatches in 51,562,642 rows). Corners are computed from the id in tenths, so they print exactly.
 */
public final class CellGrid {

    public static final double SIZE_DEG = 0.1;
    private static final long LON_CELLS = 3600;

    private CellGrid() {
    }

    /** South edge in tenths of a degree. */
    public static long southTenths(long cellId) {
        return cellId / LON_CELLS - 900;
    }

    /** West edge in tenths of a degree. */
    public static long westTenths(long cellId) {
        return cellId % LON_CELLS - 1800;
    }

    /** Closed ring [[w,s],[e,s],[e,n],[w,n],[w,s]] of the cell, counter-clockwise (GeoJSON exterior ring). */
    public static double[][] ring(long cellId) {
        double w = westTenths(cellId) / 10.0;
        double s = southTenths(cellId) / 10.0;
        double e = (westTenths(cellId) + 1) / 10.0;
        double n = (southTenths(cellId) + 1) / 10.0;
        return new double[][] {{w, s}, {e, s}, {e, n}, {w, n}, {w, s}};
    }

    /** How many cell centers ((k + 0.5) / 10) fall inside [low, high]. */
    public static long centersWithin(double low, double high) {
        long first = (long) Math.ceil(low * 10 - 0.5);
        long last = (long) Math.floor(high * 10 - 0.5);
        return Math.max(0, last - first + 1);
    }
}
