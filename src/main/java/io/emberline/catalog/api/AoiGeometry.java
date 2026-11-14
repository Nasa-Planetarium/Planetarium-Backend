package io.emberline.catalog.api;

/**
 * An AOI boundary for spatial selection in other contexts: the GeoJSON geometry as stored in the release and its
 * bbox (computed with JTS at ingest). {@code geometryJson} and the bbox are null for the AOIs without a boundary.
 */
public record AoiGeometry(String aoiId, String level, Double west, Double south, Double east, Double north,
                          String geometryJson) {

    public boolean hasBoundary() {
        return geometryJson != null && west != null;
    }
}
