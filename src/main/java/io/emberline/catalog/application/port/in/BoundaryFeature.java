package io.emberline.catalog.application.port.in;

import io.emberline.shared.json.GeoJson;
import io.emberline.shared.json.RawJson;

/** GET /api/v1/aois/{aoiId}/boundary — a GeoJSON Feature; geometry null for the 13 AOIs without a boundary. */
public record BoundaryFeature(String type, RawJson geometry, Properties properties) implements GeoJson {

    public record Properties(String aoiId, String methodVersion) {
    }
}
