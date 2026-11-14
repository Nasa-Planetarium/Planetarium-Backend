package io.emberline.hotspots.domain;

/** [west, south, east, north] in degrees (WGS84, west &lt; east, south &lt; north). */
public record BboxArea(double west, double south, double east, double north) {

    /** Plain degree area (width × height), the measure of the "zoomed in" rule. */
    public double areaDeg2() {
        return (east - west) * (north - south);
    }
}
