package io.emberline.hotspots.application.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * GET /api/v1/hotspots (contract schema HotspotList). Exactly one of {@code aoi_id} / {@code bbox} is present.
 * {@code count} = points in this page, {@code total} = every matching point of the layer, {@code truncated} = more
 * points follow ({@code next_cursor}).
 */
public record HotspotListResponse(@JsonInclude(JsonInclude.Include.NON_NULL) String aoiId,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) List<Double> bbox,
                                  String start, String end, String layer, int limit, int count, long total,
                                  boolean truncated, String nextCursor, String unit, ReferenceSum referenceSum,
                                  List<HotspotItem> hotspots, String methodVersion) {

    public HotspotListResponse {
        bbox = bbox == null ? null : List.copyOf(bbox);
        hotspots = List.copyOf(hotspots);
    }
}
