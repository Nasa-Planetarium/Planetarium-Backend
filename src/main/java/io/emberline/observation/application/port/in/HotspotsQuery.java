package io.emberline.observation.application.port.in;

/** GET /api/v1/hotspots after validation. */
public record HotspotsQuery(String bbox, String aoiId, String start, String end, String layer, Integer limit,
                            String cursor, String format) {
}
