package io.emberline.observation.domain;

/** A query box [west, south, east, north] on cell centers (WGS84, west &lt; east, south &lt; north). */
public record CellBox(double west, double south, double east, double north) {

    /** Number of 0.1° cells whose center lies in the box — the cost of a /cells request before any read. */
    public long cellsCovered() {
        return CellGrid.centersWithin(west, east) * CellGrid.centersWithin(south, north);
    }
}
