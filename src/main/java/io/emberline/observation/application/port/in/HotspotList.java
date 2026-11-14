package io.emberline.observation.application.port.in;

import java.util.List;

/** GET /api/v1/hotspots body (contract HotspotList); not produced until a release ships harmonized_hotspots. */
public record HotspotList(String start, String end, String layer, boolean truncated, Long total, String nextCursor,
                          List<Object> hotspots, String methodVersion) {
}
