package io.emberline.hotspots.application.port.in;

/**
 * Query of GET /api/v1/hotspots as sent (formats already checked by the request record): exactly one of
 * {@code bbox} ("west,south,east,north") or {@code aoiId}; {@code layer} is a code ({@code reference} / {@code all}).
 */
public record HotspotListQuery(String bbox, String aoiId, String start, String end, String layer, int limit, String cursor) {
}
