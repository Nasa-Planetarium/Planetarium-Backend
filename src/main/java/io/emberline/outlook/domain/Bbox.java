package io.emberline.outlook.domain;

/**
 * [west, south, east, north] in degrees (WGS84, contract parameter {@code BboxOptional}). A box with west &gt; east
 * crosses the antimeridian (accepted, the model team's store rule). A cell belongs to the box when its centre lies in
 * it, edges included.
 */
public record Bbox(double west, double south, double east, double north) {

    public boolean crossesAntimeridian() {
        return west > east;
    }

    /** Plain degree area (width × height); the width of an antimeridian box wraps through ±180. */
    public double areaDeg2() {
        double width = crossesAntimeridian() ? east - west + 360 : east - west;
        return width * (north - south);
    }

    public boolean contains(double lat, double lon) {
        if (lat < south || lat > north) {
            return false;
        }
        return crossesAntimeridian() ? lon >= west || lon <= east : lon >= west && lon <= east;
    }
}
