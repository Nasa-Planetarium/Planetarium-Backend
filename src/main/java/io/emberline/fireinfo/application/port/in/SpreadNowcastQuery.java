package io.emberline.fireinfo.application.port.in;

/** GET /api/v1/spread-nowcast as sent: exactly one of {@code aoi_id} / {@code bbox}; {@code min_level} (null = low). */
public record SpreadNowcastQuery(String aoiId, String bbox, String minLevel) {
}
