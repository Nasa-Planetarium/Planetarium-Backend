package io.emberline.outlook.application.port.in;

/**
 * Query of GET /api/v1/occurrence/grid as received (formats checked by the request record, rules by the service).
 *
 * @param bbox west,south,east,north or null for the whole grid
 * @param minPAny cells with a smaller p_any are left out (null = 0)
 * @param fields {@code map} or {@code full} (null = full)
 */
public record GridOccurrenceQuery(String bbox, Double minPAny, String fields) {
}
