package io.emberline.fireinfo.domain;

import java.util.List;
import java.util.Optional;

/** [west, south, east, north] in degrees (WGS84, west &lt; east, south &lt; north, longitudes ±180, latitudes ±90). */
public record Bbox(double west, double south, double east, double north) {

    /** Text {@code west,south,east,north}; empty when it is not four finite numbers forming such a box. */
    public static Optional<Bbox> parse(String text) {
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        double[] v = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                v[i] = Double.parseDouble(parts[i].strip());
                if (!Double.isFinite(v[i])) {
                    return Optional.empty();
                }
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (v[0] < -180 || v[2] > 180 || v[1] < -90 || v[3] > 90 || v[0] >= v[2] || v[1] >= v[3]) {
            return Optional.empty();
        }
        return Optional.of(new Bbox(v[0], v[1], v[2], v[3]));
    }

    /** Plain degree area (width × height), the measure of the layer bbox limit. */
    public double areaDeg2() {
        return (east - west) * (north - south);
    }

    /** Whether the two boxes share at least one point (edges count). */
    public boolean overlaps(Bbox other) {
        return west <= other.east && east >= other.west && south <= other.north && north >= other.south;
    }

    /** Whether the point lies in the box (edges count). */
    public boolean contains(double lat, double lon) {
        return lon >= west && lon <= east && lat >= south && lat <= north;
    }

    public List<Double> asList() {
        return List.of(west, south, east, north);
    }

    /** Canonical text of the box (normalized query, cursor fingerprint). */
    public String canonical() {
        return west + "," + south + "," + east + "," + north;
    }
}
