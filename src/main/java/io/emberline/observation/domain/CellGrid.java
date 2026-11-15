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

    /** Grid row of the cell: south edge in tenths + 900 (0 … 1799). */
    public static long row(long cellId) {
        return cellId / LON_CELLS;
    }

    /** Grid column of the cell: west edge in tenths + 1800 (0 … 3599). */
    public static long col(long cellId) {
        return cellId % LON_CELLS;
    }

    /** The cell id of grid row and column (the inverse of {@link #row} / {@link #col}). */
    public static long id(long row, long col) {
        return row * LON_CELLS + col;
    }

    /** Center latitude = south edge + 0.05, from the id in twentieths so it prints exactly (e.g. 36.35). */
    public static double centerLat(long cellId) {
        return (2 * southTenths(cellId) + 1) / 20.0;
    }

    /** Center longitude = west edge + 0.05, from the id in twentieths so it prints exactly. */
    public static double centerLon(long cellId) {
        return (2 * westTenths(cellId) + 1) / 20.0;
    }

    /** Tenths index k of the first cell whose center (k + 0.5) / 10 is ≥ {@code low}. */
    public static long firstCenter(double low) {
        return (long) Math.ceil(low * 10 - 0.5);
    }

    /** Tenths index k of the last cell whose center (k + 0.5) / 10 is ≤ {@code high}. */
    public static long lastCenter(double high) {
        return (long) Math.floor(high * 10 - 0.5);
    }

    /** How many cell centers ((k + 0.5) / 10) fall inside [low, high]. */
    public static long centersWithin(double low, double high) {
        return Math.max(0, lastCenter(high) - firstCenter(low) + 1);
    }
}
